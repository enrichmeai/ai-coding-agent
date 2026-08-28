package com.example.agent.llm;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Records how a model delivered its tool calls, so a model that cannot make them
 * at all stops being an invisible failure.
 *
 * <p>The agent loop acts only on structured tool calls. A model that advertises
 * {@code tools} and answers in prose instead produces a turn that looks
 * successful — assistant text, no error, nothing done. To an operator that reads
 * as a weak model rather than a misconfiguration, which is the most expensive
 * kind of bug to sit on.
 *
 * <p>Three outcomes are worth telling apart:
 * <ul>
 *   <li>{@code STRUCTURED} — the model used Ollama's {@code tool_calls} field.</li>
 *   <li>{@code RECOVERED} — it emitted the call as text and
 *       {@link com.example.agent.llm.ollama.TextToolCallParser} salvaged it. The
 *       loop works, but only because of best-effort parsing.</li>
 *   <li>{@code NONE} — a reply with no tool call at all. Usually correct (most
 *       turns are just an answer), which is why the count alone means nothing;
 *       what matters is a model that only ever lands here.</li>
 * </ul>
 *
 * <p>Deliberately does not affect health <em>status</em>. Readiness is a
 * config-only check by design, and flipping a pod out of rotation because a model
 * happened to answer three questions in a row without needing a tool would be
 * worse than the problem it reports.
 */
@Component
public class ToolCallFormatObserver {

    public enum Format { STRUCTURED, RECOVERED, NONE }

    private final AtomicLong structured = new AtomicLong();
    private final AtomicLong recovered = new AtomicLong();
    private final AtomicLong none = new AtomicLong();

    public void record(Format format) {
        switch (format) {
            case STRUCTURED -> structured.incrementAndGet();
            case RECOVERED -> recovered.incrementAndGet();
            case NONE -> none.incrementAndGet();
        }
    }

    public long structuredCount() { return structured.get(); }
    public long recoveredCount()  { return recovered.get(); }
    public long noneCount()       { return none.get(); }

    /**
     * A short verdict for the health endpoint's details.
     *
     * <p>Distinguishes "nothing seen yet" from "seen, and the model cannot do it",
     * because those call for opposite reactions: wait, versus change the model.
     */
    public String verdict() {
        long s = structured.get(), r = recovered.get(), n = none.get();
        if (s + r + n == 0) {
            return "no completions yet";
        }
        if (s > 0) {
            return r > 0
                    ? "structured tool calls seen (" + r + " recovered from text)"
                    : "structured tool calls seen";
        }
        if (r > 0) {
            return "NO structured tool calls: " + r + " recovered from text by the "
                    + "fallback parser. The model is not using the tool_calls field; "
                    + "recovery is best-effort and will not catch every format.";
        }
        return "no tool call has ever been produced in " + n + " completions. If the "
                + "agent appears to do nothing, the model is likely unable to emit "
                + "tool calls — see docs/offline-docker-compose.md.";
    }

    /** True once a model has demonstrated it can use the structured field. */
    public boolean hasProducedStructuredCall() {
        return structured.get() > 0;
    }
}
