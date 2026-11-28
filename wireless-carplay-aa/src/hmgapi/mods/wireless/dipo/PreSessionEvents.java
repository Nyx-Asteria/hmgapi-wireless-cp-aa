package hmgapi.mods.wireless.dipo;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Bounded, recent sanitized events only; no protocol bodies. Caller serializes access. */
final class PreSessionEvents {
    static final long MAX_AGE_MS=10000;
    static final int LIMIT=32;
    static final class Event {
        final String message; final long at;
        Event(String message,long at){this.message=message;this.at=at;}
    }
    private final ArrayDeque<Event> events=new ArrayDeque<Event>();
    static boolean recent(long at,long now,long previousEnd){
        return at>previousEnd && at>=now-MAX_AGE_MS && at<=now+1000;
    }
    void add(String message,long at,long now,long previousEnd){
        if(!recent(at,now,previousEnd))return;
        if(events.size()>=LIMIT)events.removeFirst();
        events.addLast(new Event(SessionLogPolicy.sanitize(message),at));
    }
    List<Event> drain(long now,long previousEnd){
        List<Event> result=new ArrayList<Event>();
        while(!events.isEmpty()){
            Event e=events.removeFirst();
            if(recent(e.at,now,previousEnd))result.add(e);
        }
        return result;
    }
}
