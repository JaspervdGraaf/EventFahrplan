package nerd.tuxmobil.fahrplan.congress.serialization;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import nerd.tuxmobil.fahrplan.congress.MyApp;
import nerd.tuxmobil.fahrplan.congress.contract.BundleKeys;
import nerd.tuxmobil.fahrplan.congress.models.Lecture;
import nerd.tuxmobil.fahrplan.congress.models.Meta;
import nerd.tuxmobil.fahrplan.congress.serialization.exceptions.MissingXmlAttributeException;
import nerd.tuxmobil.fahrplan.congress.utils.DateHelper;
import nerd.tuxmobil.fahrplan.congress.utils.FahrplanMisc;
import nerd.tuxmobil.fahrplan.congress.utils.LectureUtils;
import nerd.tuxmobil.fahrplan.congress.validation.DateFieldValidation;

import static nerd.tuxmobil.fahrplan.congress.serialization.XmlPullParsers.getSanitizedText;

public class FahrplanParser {

    public interface OnParseCompleteListener {

        void onUpdateLectures(@NonNull List<Lecture> lectures);

        void onUpdateMeta(@NonNull Meta meta);

        void onParseDone(Boolean result, String version);
    }

    private ParserTask task;

    private OnParseCompleteListener listener;

    private Context context;

    public FahrplanParser(Context context) {
        task = null;
        MyApp.parser = this;
        this.context = context;
    }

    public void parse(String fahrplan, String eTag) {
        task = new ParserTask(context, listener);
        task.execute(fahrplan, eTag);
    }

    public void cancel() {
        if (task != null) {
            task.cancel(false);
        }
    }

    public void setListener(OnParseCompleteListener listener) {
        this.listener = listener;
        if (task != null) {
            task.setListener(listener);
        }
    }
}

class ParserTask extends AsyncTask<String, Void, Boolean> {

    private List<Lecture> lectures;

    private Meta meta;

    private FahrplanParser.OnParseCompleteListener listener;

    private boolean completed;

    private boolean result;

    private Context context;

    ParserTask(Context context, FahrplanParser.OnParseCompleteListener listener) {
        this.listener = listener;
        this.completed = false;
        this.context = context;
    }

    public void setListener(FahrplanParser.OnParseCompleteListener listener) {
        this.listener = listener;

        if (completed && (listener != null)) {
            notifyActivity();
        }
    }

    @Override
    protected Boolean doInBackground(String... args) {
        boolean parsingSuccessful = parseFahrplan(args[0], args[1]);
        if (parsingSuccessful) {
            DateFieldValidation dateFieldValidation = new DateFieldValidation(context);
            dateFieldValidation.validate();
            dateFieldValidation.printValidationErrors();
            // TODO Clear database on validation failure.
        }
        return parsingSuccessful;
    }

    private void notifyActivity() {
        if (result) {
            listener.onUpdateLectures(lectures);
            listener.onUpdateMeta(meta);
        }
        listener.onParseDone(result, meta.getVersion());
        completed = false;
    }

    protected void onPostExecute(Boolean result) {
        completed = true;
        this.result = result;

        if (listener != null) {
            notifyActivity();
        }
    }

    private Boolean parseFahrplan(String fahrplan, String eTag) {
        XmlPullParser parser = Xml.newPullParser();
        try {
            parser.setInput(new StringReader(fahrplan));
            int eventType = parser.getEventType();
            boolean done = false;
            int numdays = 0;
            String room = null;
            int day = 0;
            int dayChangeTime = 600; // hardcoded as not provided
            String date = "";
            int room_index = 0;
            int room_map_index = 0;
            boolean schedule_complete = false;
            HashMap<String, Integer> roomsMap = new HashMap<>();
            while (eventType != XmlPullParser.END_DOCUMENT && !done && !isCancelled()) {
                String name;
                switch (eventType) {
                    case XmlPullParser.START_DOCUMENT:
                        lectures = new ArrayList<>();
                        meta = new Meta();
                        break;
                    case XmlPullParser.END_TAG:
                        name = parser.getName();
                        if (name.equals("schedule")) {
                            schedule_complete = true;
                        }
                        break;
                    case XmlPullParser.START_TAG:
                        name = parser.getName();
                        if (name.equals("version")) {
                            parser.next();
                            meta.setVersion(getSanitizedText(parser));
                        }
                        if (name.equals("day")) {
                            String index = parser.getAttributeValue(null, "index");
                            day = Integer.parseInt(index);
                            date = parser.getAttributeValue(null, "date");
                            String end = parser.getAttributeValue(null, "end");
                            if (end == null) {
                                throw new MissingXmlAttributeException("day", "end");
                            }
                            dayChangeTime = DateHelper.getDayChange(end);
                            if (day > numdays) {
                                numdays = day;
                            }
                        }
                        if (name.equals("room")) {
                            room = new String(parser.getAttributeValue(null, "name"));
                            if (!roomsMap.containsKey(room)) {
                                roomsMap.put(room, room_index);
                                room_map_index = room_index;
                                room_index++;
                            } else {
                                room_map_index = roomsMap.get(room);
                            }
                        }
                        if (name.equalsIgnoreCase("event")) {
                            String id = parser.getAttributeValue(null, "id");
                            Lecture lecture = new Lecture(id);
                            lecture.day = day;
                            lecture.room = room;
                            lecture.date = date;
                            lecture.room_index = room_map_index;
                            eventType = parser.next();
                            boolean lecture_done = false;
                            while (eventType != XmlPullParser.END_DOCUMENT
                                    && !lecture_done && !isCancelled()) {
                                switch (eventType) {
                                    case XmlPullParser.END_TAG:
                                        name = parser.getName();
                                        if (name.equals("event")) {
                                            lectures.add(lecture);
                                            lecture_done = true;
                                        }
                                        break;
                                    case XmlPullParser.START_TAG:
                                        name = parser.getName();
                                        if (name.equals("title")) {
                                            parser.next();
                                            lecture.title = getSanitizedText(parser);
                                        } else if (name.equals("subtitle")) {
                                            parser.next();
                                            lecture.subtitle = getSanitizedText(parser);
                                        } else if (name.equals("slug")) {
                                            parser.next();
                                            lecture.slug = getSanitizedText(parser);
                                        } else if (name.equals("track")) {
                                            parser.next();
                                            lecture.track = getSanitizedText(parser);
                                        } else if (name.equals("type")) {
                                            parser.next();
                                            lecture.type = getSanitizedText(parser);
                                        } else if (name.equals("language")) {
                                            parser.next();
                                            lecture.lang = getSanitizedText(parser);
                                        } else if (name.equals("abstract")) {
                                            parser.next();
                                            lecture.abstractt = getSanitizedText(parser);
                                        } else if (name.equals("description")) {
                                            parser.next();
                                            lecture.description = getSanitizedText(parser);
                                        } else if (name.equals("person")) {
                                            parser.next();
                                            String separator = lecture.speakers.length() > 0 ? ";" : "";
                                            lecture.speakers = lecture.speakers + separator + getSanitizedText(parser);
                                        } else if (name.equals("link")) {
                                            String url = parser.getAttributeValue(null, "href");
                                            parser.next();
                                            String urlName = getSanitizedText(parser);
                                            if (url == null) {
                                                url = urlName;
                                            }
                                            if (!url.contains("://")) {
                                                url = "http://" + url;
                                            }
                                            StringBuilder sb = new StringBuilder();
                                            if (lecture.links.length() > 0) {
                                                sb.append(lecture.links);
                                                sb.append(",");
                                            }
                                            sb.append("[").append(urlName).append("]").append("(")
                                                    .append(url).append(")");
                                            lecture.links = sb.toString();
                                        } else if (name.equals("start")) {
                                            parser.next();
                                            lecture.startTime = Lecture.parseStartTime(getSanitizedText(parser));
                                            lecture.relStartTime = lecture.startTime;
                                            if (lecture.relStartTime < dayChangeTime) {
                                                lecture.relStartTime += (24 * 60);
                                            }
                                        } else if (name.equals("duration")) {
                                            parser.next();
                                            lecture.duration = Lecture.parseDuration(getSanitizedText(parser));
                                        } else if (name.equals("date")) {
                                            parser.next();
                                            lecture.dateUTC = DateHelper.getDateTime(getSanitizedText(parser));
                                        } else if (name.equals("recording")) {
                                            eventType = parser.next();
                                            boolean recording_done = false;
                                            while (eventType != XmlPullParser.END_DOCUMENT
                                                    && !recording_done && !isCancelled()) {
                                                switch (eventType) {
                                                    case XmlPullParser.END_TAG:
                                                        name = parser.getName();
                                                        if (name.equals("recording")) {
                                                            recording_done = true;
                                                        }
                                                        break;
                                                    case XmlPullParser.START_TAG:
                                                        name = parser.getName();
                                                        if (name.equals("license")) {
                                                            parser.next();
                                                            lecture.recordingLicense = getSanitizedText(parser);
                                                        } else if (name.equals("optout")) {
                                                            parser.next();
                                                            lecture.recordingOptOut = Boolean.valueOf(getSanitizedText(parser));
                                                        }
                                                        break;
                                                }
                                                if (recording_done) {
                                                    break;
                                                }
                                                eventType = parser.next();
                                            }
                                        }
                                        break;
                                }
                                if (lecture_done) {
                                    break;
                                }
                                eventType = parser.next();
                            }
                        } else if (name.equalsIgnoreCase("conference")) {
                            boolean conf_done = false;
                            eventType = parser.next();
                            while (eventType != XmlPullParser.END_DOCUMENT
                                    && !conf_done) {
                                switch (eventType) {
                                    case XmlPullParser.END_TAG:
                                        name = parser.getName();
                                        if (name.equals("conference")) {
                                            conf_done = true;
                                        }
                                        break;
                                    case XmlPullParser.START_TAG:
                                        name = parser.getName();
                                        if (name.equals("subtitle")) {
                                            parser.next();
                                            meta.setSubtitle(getSanitizedText(parser));
                                        }
                                        if (name.equals("title")) {
                                            parser.next();
                                            meta.setTitle(getSanitizedText(parser));
                                        }
                                        if (name.equals("release")) {
                                            parser.next();
                                            meta.setVersion(getSanitizedText(parser));
                                        }
                                        if (name.equals("day_change")) {
                                            parser.next();
                                            dayChangeTime = Lecture.parseStartTime(getSanitizedText(parser));
                                        }
                                        break;
                                }
                                if (conf_done) {
                                    break;
                                }
                                eventType = parser.next();
                            }
                        }
                        break;
                }
                eventType = parser.next();
            }
            if (!schedule_complete) {
                return false;
            }
            if (isCancelled()) {
                return false;
            }
            setChangedFlags(lectures);
            if (isCancelled()) {
                return false;
            }
            meta.setNumDays(numdays);
            meta.setETag(eTag);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private void setChangedFlags(List<Lecture> lectures) {
        List<Lecture> oldLectures;
        boolean changed = false;

        oldLectures = FahrplanMisc.loadLecturesForAllDays(this.context);
        if (oldLectures.isEmpty()) {
            return;
        }

        int lectureIndex = oldLectures.size() - 1;
        while (lectureIndex >= 0) {
            Lecture l = oldLectures.get(lectureIndex);
            if (l.changedIsCanceled) oldLectures.remove(lectureIndex);
            lectureIndex--;
        }

        for (lectureIndex = 0; lectureIndex < lectures.size(); lectureIndex++) {
            Lecture newLecture = lectures.get(lectureIndex);
            Lecture oldLecture = LectureUtils.getLecture(oldLectures, newLecture.lecture_id);

            if (oldLecture == null) {
                newLecture.changedIsNew = true;
                changed = true;
                continue;
            }

            if (oldLecture.equals(newLecture)) {
                oldLectures.remove(oldLecture);
                continue;
            }

            if (!(newLecture.title.equals(oldLecture.title))) {
                newLecture.changedTitle = true;
                changed = true;
            }
            if (!(newLecture.subtitle.equals(oldLecture.subtitle))) {
                newLecture.changedSubtitle = true;
                changed = true;
            }
            if (!(newLecture.speakers.equals(oldLecture.speakers))) {
                newLecture.changedSpeakers = true;
                changed = true;
            }
            if (!(newLecture.lang.equals(oldLecture.lang))) {
                newLecture.changedLanguage = true;
                changed = true;
            }
            if (!(newLecture.room.equals(oldLecture.room))) {
                newLecture.changedRoom = true;
                changed = true;
            }
            if (!(newLecture.track.equals(oldLecture.track))) {
                newLecture.changedTrack = true;
                changed = true;
            }
            if (newLecture.recordingOptOut != oldLecture.recordingOptOut) {
                newLecture.changedRecordingOptOut = true;
                changed = true;
            }
            if (newLecture.day != oldLecture.day) {
                newLecture.changedDay = true;
                changed = true;
            }
            if (newLecture.startTime != oldLecture.startTime) {
                newLecture.changedTime = true;
                changed = true;
            }
            if (newLecture.duration != oldLecture.duration) {
                newLecture.changedDuration = true;
                changed = true;
            }

            oldLectures.remove(oldLecture);
        }

        for (lectureIndex = 0; lectureIndex < oldLectures.size(); lectureIndex++) {
            Lecture oldLecture = oldLectures.get(lectureIndex);
            oldLecture.cancel();
            lectures.add(oldLecture);
            changed = true;
        }

        if (changed) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this.context);
            SharedPreferences.Editor edit = prefs.edit();
            edit.putBoolean(BundleKeys.PREFS_CHANGES_SEEN, false);
            edit.apply();
        }
    }

}
