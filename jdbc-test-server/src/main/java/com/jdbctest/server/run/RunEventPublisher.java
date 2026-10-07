package com.jdbctest.server.run;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
final class RunEventPublisher {
    private final Map<UUID, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();
    SseEmitter subscribe(UUID runId, Object snapshot) {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.compute(runId, (id, values) -> { var subscribers = values == null ? new CopyOnWriteArrayList<SseEmitter>() : values; subscribers.add(emitter); return subscribers; });
        Runnable cleanup = () -> remove(runId, emitter);
        emitter.onCompletion(cleanup); emitter.onTimeout(cleanup); emitter.onError(error -> cleanup.run());
        send(runId, emitter, "run.snapshot", snapshot);
        return emitter;
    }
    void publish(UUID runId, String name, Object body) { for (SseEmitter emitter : emitters.getOrDefault(runId, new CopyOnWriteArrayList<>())) send(runId, emitter, name, body); }
    void complete(UUID runId) { var values = emitters.remove(runId); if (values != null) for (SseEmitter emitter : values) emitter.complete(); }
    private void send(UUID runId, SseEmitter emitter, String name, Object body) { try { emitter.send(SseEmitter.event().name(name).data(body)); } catch (IOException | IllegalStateException exception) { remove(runId, emitter); emitter.complete(); } }
    private void remove(UUID runId, SseEmitter emitter) { emitters.computeIfPresent(runId, (id, values) -> { values.remove(emitter); return values.isEmpty() ? null : values; }); }
}
