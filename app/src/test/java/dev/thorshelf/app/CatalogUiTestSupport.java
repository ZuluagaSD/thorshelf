package dev.thorshelf.app;

import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import static org.junit.Assert.fail;
import static org.robolectric.Shadows.shadowOf;

/** Deterministic, network-free catalog responses shared by activity tests. */
final class CatalogUiTestSupport {
    static final String TITLE = "https://hshop.erista.me/t/2476";
    static final String DOWNLOAD = "https://download4.erista.me/content/2476?token=fixture-only";
    static final String GAME = "Pokemon Omega Ruby";

    static CatalogPage results(String url, String title) {
        return new CatalogPage(url, title, "showing 1 - 1 of 1",
                Collections.singletonList(new CatalogPage.Entry(TITLE, GAME, "games", "world", "1.77 GiB", "Legit", "000400000011C400")),
                Collections.emptyList(), "", "", "", false);
    }

    static CatalogPage detail(String downloadUrl) {
        CatalogPage result = results(TITLE, GAME);
        return new CatalogPage(TITLE, GAME, "", result.entries, result.categories, "", "", downloadUrl, false);
    }

    static CatalogPage category(String url) {
        return new CatalogPage(url, "Games", "Choose a region", Collections.emptyList(),
                Collections.singletonList(new CatalogPage.Category(url + "/s/world", "World", "Games released worldwide.")),
                "", "", "", false);
    }

    static void await(String description, BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            shadowOf(Looper.getMainLooper()).idle();
            if (condition.getAsBoolean()) return;
            try { Thread.sleep(5); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); fail("Interrupted: " + description); }
        } while (System.nanoTime() < deadline);
        fail("Timed out waiting for " + description);
    }

    static <T extends View> T find(View view, Class<T> type, Predicate<T> predicate) {
        if (type.isInstance(view) && predicate.test(type.cast(view))) return type.cast(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                T found = find(group.getChildAt(i), type, predicate);
                if (found != null) return found;
            }
        }
        return null;
    }

    static final class Gate {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final boolean ignoreInterrupt;
        volatile Thread thread;
        Gate(boolean ignoreInterrupt) { this.ignoreInterrupt = ignoreInterrupt; }
        void waitForRelease() throws IOException {
            thread = Thread.currentThread();
            entered.countDown();
            boolean waiting = true;
            while (waiting) {
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test request gate timed out");
                    waiting = false;
                } catch (InterruptedException interrupted) {
                    if (!ignoreInterrupt) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Test request interrupted", interrupted);
                    }
                }
            }
        }
    }

    static final class FakeClient extends CatalogClient {
        final List<String> fetched = new CopyOnWriteArrayList<>();
        final List<String> inspected = new CopyOnWriteArrayList<>();
        final Map<String, CatalogPage> pages = new ConcurrentHashMap<>();
        final Map<String, IOException> failures = new ConcurrentHashMap<>();
        final Map<String, Gate> gates = new ConcurrentHashMap<>();
        volatile Gate inspectionGate;
        volatile IOException inspectionFailure;
        volatile String inspectedReferer;
        volatile String inspectedAgent;
        volatile DownloadInfo file = new DownloadInfo(DOWNLOAD, GAME + ".cia", "application/octet-stream", 1234);

        @Override public CatalogPage fetch(String url, String userAgent, String cookie) throws IOException {
            fetched.add(url);
            Gate gate = gates.get(url);
            try {
                if (gate != null) gate.waitForRelease();
                IOException failure = failures.get(url);
                if (failure != null) throw failure;
                CatalogPage page = pages.get(url);
                return page == null ? results(url, "Catalog results") : page;
            } finally {
                if (gate != null) gate.finished.countDown();
            }
        }

        @Override public DownloadInfo inspectDownload(String url, String userAgent, String cookie, String referer) throws IOException {
            inspected.add(url);
            inspectedReferer = referer;
            inspectedAgent = userAgent;
            Gate gate = inspectionGate;
            try {
                if (gate != null) gate.waitForRelease();
                if (inspectionFailure != null) throw inspectionFailure;
                return file;
            } finally {
                if (gate != null) gate.finished.countDown();
            }
        }

        long fetchCount(String url) { return fetched.stream().filter(url::equals).count(); }
        void releaseAll() {
            for (Gate gate : gates.values()) gate.release.countDown();
            if (inspectionGate != null) inspectionGate.release.countDown();
        }
    }
}
