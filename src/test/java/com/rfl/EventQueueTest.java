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
        assertEquals(List.of("Y"), q.drain(EventQueue.MAX_BATCH).stream().map(e -> e.plugin).collect(toList()));
    }

    @Test
    public void drainCapsBatchAndKeepsTheRestInOrder()
    {
        EventQueue q = new EventQueue();
        for (int i = 0; i < 450; i++)
        {
            q.add(RflEvent.pluginToggle(i, i, "P" + i, true));
        }

        List<RflEvent> first = q.drain(EventQueue.MAX_BATCH);
        assertEquals(400, first.size());
        assertEquals("P0", first.get(0).plugin);
        assertEquals("P399", first.get(399).plugin);

        List<RflEvent> second = q.drain(EventQueue.MAX_BATCH);
        assertEquals(50, second.size());
        assertEquals("P400", second.get(0).plugin);
        assertEquals("P449", second.get(49).plugin);
        assertEquals(0, q.drain(EventQueue.MAX_BATCH).size());
    }

    @Test
    public void requeuePutsEventsBackFirst()
    {
        EventQueue q = new EventQueue();
        q.add(RflEvent.pluginToggle(0, 0, "A", true));
        List<RflEvent> drained = q.drain(EventQueue.MAX_BATCH);

        q.add(RflEvent.pluginToggle(1, 1, "B", true));
        q.requeue(drained);

        assertEquals(List.of("A", "B"), q.drain(EventQueue.MAX_BATCH).stream().map(e -> e.plugin).collect(toList()));
    }
}
