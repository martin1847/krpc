package tech.krpc.examples.quickstart;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

/**
 * OTEL-003 (test scope only): a minimal in-memory {@link SpanExporter} built on {@code
 * opentelemetry-sdk} alone.
 *
 * <p><b>Why not {@code InMemorySpanExporter} from {@code opentelemetry-sdk-testing}?</b> That
 * artifact also registers {@code SettableContextStorageProvider} via the
 * {@code io.opentelemetry.context.ContextStorageProvider} SPI, and OTel's {@code LazyStorage}
 * short-circuits to it the moment it appears on the classpath — <em>before</em> any other provider
 * or the {@code contextStorageProvider} system property is even considered. That silently pins the
 * container tests to a plain ThreadLocal storage, which is NOT what a real Quarkus consumer runs on
 * (it resolves {@code QuarkusContextStorage} via the quarkus-opentelemetry SPI). Dropping
 * sdk-testing lets the Quarkus provider win, so these tests exercise the SAME context-propagation
 * path as staging. See {@code docs/orchestration/OTEL-003_IMPL_omp.md}.
 */
public final class RecordingSpanExporter implements SpanExporter {

    private final List<SpanData> spans = new CopyOnWriteArrayList<>();

    @Override
    public CompletableResultCode export(Collection<SpanData> collection) {
        spans.addAll(collection);
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }

    /** All spans exported so far (mirrors {@code InMemorySpanExporter.getFinishedSpanItems}). */
    public List<SpanData> getFinishedSpanItems() {
        return List.copyOf(spans);
    }

    public void reset() {
        spans.clear();
    }
}
