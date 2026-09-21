/*
 *  This file is part of android-tree-sitter.
 *
 *  android-tree-sitter library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  android-tree-sitter library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *  along with android-tree-sitter.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.treesitter;

import com.itsaky.androidide.treesitter.annotations.GenerateNativeHeaders;
import com.itsaky.androidide.treesitter.string.UTF16String;
import com.itsaky.androidide.treesitter.string.UTF16StringFactory;
import com.itsaky.androidide.treesitter.util.TSObjectFactoryProvider;
import dalvik.annotation.optimization.CriticalNative;
import dalvik.annotation.optimization.FastNative;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.LongFunction;

/**
 * Implementation of tree sitter's <code>TSParser</code> APIs. This implementation always converts
 * the input source code to {@link UTF16String UTF-16 string}.
 */
public class TSParser extends TSNativeObject {

  protected final ReentrantLock parseLock = new ReentrantLock();
  protected final Condition parseCondition = parseLock.newCondition();
  protected final AtomicBoolean isParsing = new AtomicBoolean(false);
  protected final AtomicBoolean isCancellationRequested = new AtomicBoolean(false);
  protected volatile boolean isClosed = false;

  /**
   * Guards the lifetime of the native parser. Every native call holds the read lock, and
   * {@link #close()} holds the write lock, so the native object cannot be deleted while a call is
   * in flight.
   */
  private final ReentrantReadWriteLock lifetimeLock = new ReentrantReadWriteLock(true);

  protected TSParser(long pointer) {
    super(pointer);
  }

  protected TSParser() {
    this(Native.newParser());
  }

  public static TSParser create() {
    return create(Native.newParser());
  }

  public static TSParser create(long parserPointer) {
    return TSObjectFactoryProvider.getFactory().createParser(parserPointer);
  }

  private TSTree createTree(long pointer) {
    if (pointer == 0) {
      return null;
    }
    return TSTree.create(pointer);
  }

  /**
   * Set the language of the given parser.
   *
   * @param language The language to set.
   * @see TSLanguage
   */
  public void setLanguage(TSLanguage language) {
    final var languagePointer = language.getNativeObject();
    withNativeParser(parser -> {
      Native.setLanguage(parser, languagePointer);
      return null;
    });
  }

  private <T> T withNativeParser(LongFunction<T> action) {
    final var read = lifetimeLock.readLock();
    read.lock();
    try {
      checkAccess();
      return action.apply(getNativeObject());
    } finally {
      read.unlock();
    }
  }

  /**
   * Get the language for this parser instance.
   *
   * @return The language instance.
   */
  public TSLanguage getLanguage() {
    final var langPtr = withNativeParser(Native::getLanguage);
    if (langPtr == 0) {
      return null;
    }

    return TSLanguageCache.get(langPtr);
  }

  /**
   * Parses the given String source. See {@link #parseString(TSTree, UTF16String)} for more
   * details.
   *
   * @param source The source code to parse.
   * @return The parsed tree, or <code>null</code> if the parse failed or was cancelled.
   */
  public TSTree parseString(String source) {
    throwIfParseNotCancelled();
    try (final var str = UTF16StringFactory.newString(source)) {
      return parseString(str);
    }
  }

  /**
   * Parses the given {@link UTF16String} source. See {@link #parseString(TSTree, UTF16String)} for
   * more details.
   *
   * @param source The source code to parse.
   * @return The parsed tree, or <code>null</code> if the parse failed or was cancelled.
   */
  public TSTree parseString(UTF16String source) {
    return parseString(null, source);
  }

  /**
   * Parses the given source code bytes. See {@link #parseString(TSTree, UTF16String)} for more
   * details.
   *
   * @param bytes The source code to parse.
   * @return The parsed tree, or <code>null</code> if the parse failed or was cancelled.
   * @see #parseBytes(byte[], int, int)
   */
  public TSTree parseBytes(byte[] bytes) {
    return parseBytes(bytes, 0, bytes.length);
  }

  /**
   * Parses the given source code bytes. See {@link #parseString(TSTree, UTF16String)} for more
   * details. See {@link #parseString(TSTree, UTF16String)} for more details.
   *
   * @param bytes  The source code to parse.
   * @param offset The start offset in <code>bytes</code>.
   * @param len    The number of bytes to from <code>offset</code> to parse.
   * @return The parsed tree, or <code>null</code> if the parse failed or was cancelled.
   */
  public TSTree parseBytes(byte[] bytes, int offset, int len) {
    throwIfParseNotCancelled();
    try (final var source = UTF16StringFactory.newString(bytes, offset, len)) {
      return parseString(source);
    }
  }

  /**
   * Parse the given edited source code using the previously parsed syntax tree. The given
   * {@link TSTree} must have been edited using {@link TSTree#edit(TSInputEdit)} before calling this
   * method. See {@link #parseString(TSTree, UTF16String)} for more details.
   *
   * @param oldTree The previously parsed syntax tree.
   * @param source  The source code to parse.
   * @return The parsed tree, or <code>null</code> if the parse failed or was cancelled.
   */
  public TSTree parseString(TSTree oldTree, String source) {
    throwIfParseNotCancelled();
    try (final var str = UTF16StringFactory.newString(source)) {
      return parseString(oldTree, str);
    }
  }

  /**
   * Parse the given edited source code using the previously parsed syntax tree. The given
   * {@link TSTree} must have been edited using {@link TSTree#edit(TSInputEdit)} before calling this
   * method.
   * <p>
   * Throws {@link ParseInProgressException} if the parser is currently parsing a syntax tree and
   * the cancellation was NOT requested using {@link #requestCancellationAsync()}. This method
   * blocks the current thread if the previous parse was requested to be cancelled but the parse
   * operation has not been cancelled yet.
   *
   * @param oldTree The previously parsed syntax tree.
   * @param source  The source code to parse.
   * @return The parsed tree, or <code>null</code> if the parse failed or was cancelled.
   * @throws IllegalStateException    If the parser is not accessible. See
   *                                  {@link TSNativeObject#canAccess()} for more details.
   * @throws ParseInProgressException If the parser is currently parsing another syntax tree.
   */
  public TSTree parseString(TSTree oldTree, UTF16String source) {
    final long tree;

    // held for the whole parse so that close() cannot delete the native parser under it
    final var read = lifetimeLock.readLock();
    read.lock();
    try {
      tree = parseStringLocked(oldTree, source);
    } finally {
      read.unlock();
    }

    /*
     * createTree() runs the caller supplied TSObjectFactory, which may call back into this
     * parser. Building the tree outside the read lock keeps a close() from that callback from
     * deadlocking, since a read lock cannot be upgraded to a write lock.
     */
    return createTree(tree);
  }

  private long parseStringLocked(TSTree oldTree, UTF16String source) {
    checkAccess();

    if (isClosed) {
      throw new IllegalStateException("TSParser has been closed");
    }

    // Check for reentrancy (same thread calling this method again, before the previous call returned)
    if (parseLock.isHeldByCurrentThread()) {
      throw new IllegalStateException("Reentrancy detected");
    }

    // if the parser is currently parsing a syntax tree and the cancellation
    // was not requested, throw an error
    throwIfParseNotCancelled();

    parseLock.lock();
    try {
      // close() may have started while this thread was waiting for parseLock.
      if (isClosed) {
        throw new IllegalStateException("TSParser has been closed");
      }

      checkAccess();

      setCancellationRequested(false);
      setParsingFlag();

      try {
        final long parserPointer = getNativeObject();
        final long strPointer = source.getNativeObject();
        final long oldTreePointer =
            oldTree != null ? oldTree.getNativeObject() : 0;

        return Native.parse(parserPointer, oldTreePointer, strPointer);
      } finally {
        unsetParsingFlag();
        parseCondition.signalAll();
      }
    } finally {
      parseLock.unlock();
    }
  }

  /**
   * Set the maximum duration in microseconds that parsing should be allowed to take before
   * halting.
   *
   * <p>If parsing takes longer than this, it will halt early, returning <code>null</code>.
   */
  public void setTimeout(long microseconds) {
    withNativeParser(parser -> {
      Native.setTimeout(parser, microseconds);
      return null;
    });
  }

  /**
   * Get the duration in microseconds that parsing is allowed to take.
   *
   * @return The timeout in microseconds.
   */
  public long getTimeout() {
    return withNativeParser(Native::getTimeout);
  }

  /**
   * Check whether the parser is in the process of parsing a syntax tree.
   *
   * @return <code>true</code> if the parser is parsing a syntax tree, <code>false</code> otherwise.
   */
  public boolean isParsing() {
    return isParsing.get();
  }

  /**
   * Sets the 'parsing' flag to indicate that the parser is in the process of parsing a syntax
   * tree.
   */
  protected boolean setParsingFlag() {
    return this.isParsing.compareAndSet(false, true);
  }

  /**
   * Sets the 'parsing' flag to indicate that the parsing operation is NOT in progress.
   */
  protected boolean unsetParsingFlag() {
    return this.isParsing.compareAndSet(true, false);
  }

  /**
   * Request the parsing operation to be cancelled if the parser is in the process of parsing a
   * syntax tree.
   * <p>
   * This is an asynchronous operation and the previous parse call may NOT be cancelled immediately.
   * Use {@link #requestCancellationAndWait()} for a blocking cancellation request.
   *
   * @return <code>true</code> if the cancellation was requested successfully, <code>false</code>
   * otherwise.
   */
  public boolean requestCancellationAsync() {
    final boolean requested = withNativeParser(Native::requestCancellation);
    setCancellationRequested(requested);
    return requested;
  }

  /**
   * If the parser is parsing syntax tree, sets the cancellation flag and blocks the current thread
   * until the parse operation returns. Does nothing if {@link #requestCancellationAsync()} returns
   * false.
   */
  public void requestCancellationAndWait() {
    if (requestCancellationAsync()) {
      parseLock.lock();
      try {
        while (isParsing()) {
          parseCondition.await();
        }
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      } finally {
        parseLock.unlock();
      }
    }
  }

  protected synchronized void setCancellationRequested(boolean isRequested) {
    this.isCancellationRequested.set(isRequested);
  }

  protected synchronized boolean isCancellationRequested() {
    return this.isCancellationRequested.get();
  }

  /**
   * Set the ranges of text that the parser should include when parsing.
   *
   * <p>By default, the parser will always include entire documents. This function allows you to
   * parse only a *portion* of a document but still return a syntax tree whose ranges match up with
   * the document as a whole. You can also pass multiple disjoint ranges.
   *
   * <p>If the ranges parameter is an empty array, then the entire document will be parsed.
   * Otherwise, the given ranges must be ordered from earliest to latest in the document, and they
   * must not overlap. That is, the following must hold for all `i` < `length - 1`:
   * ranges[i].end_byte <= ranges[i + 1].start_byte where `length` is the length of the ranges
   * array.
   *
   * <p>If this requirement is not satisfied, the operation will fail, the ranges will not be
   * assigned, and this function will return `false`. On success, this function returns `true`
   */
  public boolean setIncludedRanges(TSRange[] ranges) {
    return withNativeParser(parser -> Native.setIncludedRanges(parser, ranges));
  }

  public TSRange[] getIncludedRanges() {
    return withNativeParser(Native::getIncludedRanges);
  }

  /**
   * Instruct the parser to start the next parse from the beginning.
   *
   * <p>If the parser previously failed because of a timeout or a cancellation, then by default, it
   * will resume where it left off on the next call to any of the parsing functions. If you don't
   * want to resume, and instead intend to use this parser to parse some other document, you must
   * call this function first.
   */
  public void reset() {
    withNativeParser(parser -> {
      Native.reset(parser);
      return null;
    });
  }

  @Override
  public void close() {
    /*
     * getIncludedRanges() calls the object factory from native code while this thread holds the
     * read lock, so a close() from such a callback cannot be hoisted out the way parseString()
     * does it. Fail loudly instead of parking forever on a lock this thread already holds.
     */
    if (lifetimeLock.getReadHoldCount() > 0) {
      throw new IllegalStateException(
        "close() called from a parser callback, which would deadlock");
    }

    synchronized (this) {
      if (isClosed) {
        return;
      }

      isClosed = true;
    }

    // reject a parse which has not reached the native parser yet, and cancel one already running
    if (getNativeObject() != 0) {
      Native.beginClose(getNativeObject());
    }

    final var write = lifetimeLock.writeLock();
    write.lock();
    try {
      parseLock.lock();
      try {
        super.close();
      } finally {
        parseLock.unlock();
      }
    } finally {
      write.unlock();
    }
  }

  @Override
  protected void closeNativeObj() {
    Native.delete(getNativeObject());
  }

  private void throwIfParseNotCancelled() {
    if (isParsing() && !isCancellationRequested()) {
      throw new ParseInProgressException(
        "Parser is already parsing another syntax tree! Cancel the previous parse before starting another.");
    }
  }

  /**
   * Base class the {@link TSParser} exceptions.
   */
  protected static class TSParserException extends RuntimeException {

    public TSParserException(String message) {
      super(message);
    }

    public TSParserException(String message, Throwable cause) {
      super(message, cause);
    }

    public TSParserException(Throwable cause) {
      super(cause);
    }
  }

  /**
   * Thrown when a parse is requested while another parse is already in progress.
   */
  protected static final class ParseInProgressException extends TSParserException {

    public ParseInProgressException(String message) {
      super(message);
    }
  }

  @GenerateNativeHeaders(fileName = "parser")
  private static class Native {

    @FastNative
    static native long newParser();

    /* not FastNative: both block until an active parse finishes, and a FastNative method
       keeps the thread unsuspendable, which stalls every other thread at the next safepoint */
    static native void delete(long parser);

    static native void beginClose(long parser);

    @FastNative
    static native void setLanguage(long parser, long language);

    @FastNative
    static native long getLanguage(long parser);

    @FastNative
    static native void reset(long parser);

    @FastNative
    static native void setTimeout(long parser, long timeout);

    @FastNative
    static native long getTimeout(long parser);

    @FastNative
    static native boolean setIncludedRanges(long parser, TSRange[] ranges);

    @FastNative
    static native TSRange[] getIncludedRanges(long parser);

    @FastNative
    static native long parse(long parser, long treePointer, long strPointer);

    @FastNative
    static native boolean requestCancellation(long parser);
  }
}