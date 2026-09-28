package com.rfl;

import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertEquals;

import java.util.List;

import org.junit.Test;

public class EventQueueTest
{
    @Test
    public void dropsEventsOlderThanFiveMinutes()
    {
        EventQueue q = new EventQueue();
        q.add(RflEvent.pluginToggle(0, 0, "X", true));
        q.add(RflEvent.pluginToggle(300_001, 1, "Y", true));
        assertEquals(List.of("Y"), q.drain().stream().map(e -> e.plugin).collect(toList()));
    }

    @Test
    public void requeuePutsEventsBackFirst()
    {
        EventQueue q = new EventQueue();
        q.add(RflEvent.pluginToggle(0, 0, "A", true));
        List<RflEvent> drained = q.drain();

        q.add(RflEvent.pluginToggle(1, 1, "B", true));
        q.requeue(drained);

        assertEquals(List.of("A", "B"), q.drain().stream().map(e -> e.plugin).collect(toList()));
    }
}
