/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/\>.
 */

package com.itsaky.androidide.treesitter;

import static com.google.common.truth.Truth.assertThat;

import com.itsaky.androidide.treesitter.java.TSLanguageJava;
import com.itsaky.androidide.treesitter.string.UTF16StringFactory;
import com.itsaky.androidide.treesitter.util.TSObjectFactory;
import com.itsaky.androidide.treesitter.util.TSObjectFactoryProvider;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class ParserCloseTest extends TreeSitterTest {

  @Test
  public void testCloseInvokesCloseNativeObjOnSubclass() {
    final var parser = new RecordingParser();
    parser.setLanguage(TSLanguageJava.getInstance());

    parser.close();

    assertThat(parser.closeNativeObjCalled.get()).isTrue();
  }

  @Test
  public void testParseStartingWhileCloseIsPendingIsRejected() throws Exception {
    final var parser = new SlowStartParser();
    parser.setLanguage(TSLanguageJava.getInstance());

    try (final var source = UTF16StringFactory.newString("class Main {}")) {
      final var parseFailure = new AtomicReference<Throwable>();
      final var parseThread = new Thread(() -> parser.parseString(source), "parse");
      parseThread.setUncaughtExceptionHandler((thread, thrown) -> parseFailure.set(thrown));
      parseThread.start();

      // the parse now holds parseLock but has not entered the native parser yet
      assertThat(parser.enteredParseWindow.await(10, TimeUnit.SECONDS)).isTrue();

      final var closeThread = new Thread(parser::close, "close");
      closeThread.start();
      awaitParked(closeThread);

      parser.mayStartParsing.countDown();

      parseThread.join(TimeUnit.SECONDS.toMillis(30));
      closeThread.join(TimeUnit.SECONDS.toMillis(30));

      assertThat(parseFailure.get()).isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  public void testCloseFromObjectFactoryCallbackDoesNotDeadlock() throws Exception {
    final var originalFactory = TSObjectFactoryProvider.getFactory();
    final var parser = TSParser.create();
    parser.setLanguage(TSLanguageJava.getInstance());

    // a parser callback that closes the parser it was invoked for
    final var closingFactory = (TSObjectFactory) Proxy.newProxyInstance(
      TSObjectFactory.class.getClassLoader(),
      new Class<?>[] {TSObjectFactory.class},
      (proxy, method, args) -> {
        if ("createTree".equals(method.getName())) {
          parser.close();
        }
        return method.invoke(originalFactory, args);
      });

    final var executor = Executors.newSingleThreadExecutor();
    try (final var source = UTF16StringFactory.newString("class Main {}")) {
      TSObjectFactoryProvider.setFactory(closingFactory);

      final var parse = executor.submit(() -> parser.parseString(source));

      // the parse thread must not block forever on a lock it already holds
      parse.get(15, TimeUnit.SECONDS);
    } finally {
      TSObjectFactoryProvider.setFactory(originalFactory);
      executor.shutdownNow();
    }
  }

  @Test
  public void testParsingFlagIsClearedWhenTheParseThrows() {
    final var parser = new ThrowingParser();
    parser.setLanguage(TSLanguageJava.getInstance());

    try (final var source = UTF16StringFactory.newString("class Main {}")) {
      try {
        parser.parseString(source);
        throw new AssertionError("expected the parse to throw");
      } catch (IllegalStateException expected) {
        // the parser must not stay stuck in the parsing state
      }

      assertThat(parser.isParsing()).isFalse();
    }
  }

  /**
   * A parser which fails once the parsing flag has been published, which is the window between
   * setting that flag and entering the native parser.
   */
  private static final class ThrowingParser extends TSParser {

    @Override
    public long getNativeObject() {
      if (isParsing()) {
        throw new IllegalStateException("native pointer unavailable");
      }
      return super.getNativeObject();
    }
  }

  private static void awaitParked(Thread thread) throws InterruptedException {
    final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      final var state = thread.getState();
      if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING
          || state == Thread.State.BLOCKED) {
        return;
      }
      Thread.sleep(1);
    }
    throw new AssertionError("Thread '" + thread.getName() + "' never parked");
  }

  /**
   * A parser which pauses between acquiring the parse lock and publishing the parsing flag, which
   * is the window in which a concurrent close() sees no parse in progress.
   */
  private static final class SlowStartParser extends TSParser {

    final CountDownLatch enteredParseWindow = new CountDownLatch(1);
    final CountDownLatch mayStartParsing = new CountDownLatch(1);

    @Override
    protected boolean setParsingFlag() {
      enteredParseWindow.countDown();
      try {
        mayStartParsing.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
      return super.setParsingFlag();
    }
  }

  /**
   * A parser which records whether the {@link TSNativeObject#closeNativeObj()} extension point was
   * invoked. Subclasses rely on it to release resources they own.
   */
  private static final class RecordingParser extends TSParser {

    final AtomicBoolean closeNativeObjCalled = new AtomicBoolean(false);

    @Override
    protected void closeNativeObj() {
      closeNativeObjCalled.set(true);
      super.closeNativeObj();
    }
  }
}
