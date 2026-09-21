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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class ParserRaceTest extends TreeSitterTest {

  @Test
  public void testConcurrentResetAndCloseIsSafe() throws Exception {
    for (int attempt = 0; attempt < 200; attempt++) {
      final var parser = TSParser.create();
      parser.setLanguage(TSLanguageJava.getInstance());

      final var start = new CountDownLatch(1);

      final var resetter = new Thread(() -> {
        awaitQuietly(start);
        for (int i = 0; i < 50; i++) {
          try {
            parser.reset();
          } catch (IllegalStateException closed) {
            return;
          }
        }
      }, "reset");

      final var closer = new Thread(() -> {
        awaitQuietly(start);
        parser.close();
      }, "close");

      resetter.start();
      closer.start();
      start.countDown();

      resetter.join(TimeUnit.SECONDS.toMillis(30));
      closer.join(TimeUnit.SECONDS.toMillis(30));
    }

    assertThat(true).isTrue();
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
