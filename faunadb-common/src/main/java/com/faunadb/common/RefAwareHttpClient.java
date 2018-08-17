package com.faunadb.common;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.BoundRequestBuilder;
import org.asynchttpclient.Request;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

final class RefAwareHttpClient implements AutoCloseable {

  private static final int INITIAL_REF_COUNT = 1;

  private final AtomicInteger refCount = new AtomicInteger(INITIAL_REF_COUNT);
  private final AsyncHttpClient delegate;

  RefAwareHttpClient(AsyncHttpClient delegate) {
    this.delegate = delegate;
  }

  boolean retain() {
    return refCount.incrementAndGet() > INITIAL_REF_COUNT && !delegate.isClosed();
  }

  @Override
  public void close() {
    if (refCount.decrementAndGet() < INITIAL_REF_COUNT && !delegate.isClosed()) {
      try {
        delegate.close();
      } catch (IOException e) {
      }
    }
  }

  BoundRequestBuilder prepareRequest(Request request) {
    return delegate.prepareRequest(request);
  }

}
