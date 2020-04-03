/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.rsocket.internal;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.assertj.core.api.Assertions;
import org.junit.Test;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.Fuseable;
import reactor.core.publisher.EmitterProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.ReplayProcessor;
import reactor.core.publisher.Signal;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;
import reactor.test.util.RaceTestUtils;
import reactor.util.context.Context;

public class FluxSwitchOnFirstTest {

  @Test
  public void shouldNotSubscribeTwice() {
    Throwable[] throwables = new Throwable[1];
    CountDownLatch latch = new CountDownLatch(1);
    StepVerifier.create(
            Flux.just(1L)
                .transform(
                    flux ->
                        new FluxSwitchOnFirst<>(
                            flux,
                            (s, f) -> {
                              RaceTestUtils.race(
                                  () ->
                                      f.subscribe(
                                          __ -> {},
                                          t -> {
                                            throwables[0] = t;
                                            latch.countDown();
                                          },
                                          latch::countDown),
                                  () ->
                                      f.subscribe(
                                          __ -> {},
                                          t -> {
                                            throwables[0] = t;
                                            latch.countDown();
                                          },
                                          latch::countDown));

                              return Flux.empty();
                            },
                            false)))
        .expectSubscription()
        .expectComplete()
        .verify();

    Assertions.assertThat(throwables[0])
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("FluxSwitchOnFirst allows only one Subscriber");
  }

  @Test
  public void shouldNotSubscribeTwiceConditional() {
    Throwable[] throwables = new Throwable[1];
    CountDownLatch latch = new CountDownLatch(1);
    StepVerifier.create(
            Flux.just(1L)
                .transform(
                    flux ->
                        new FluxSwitchOnFirst<>(
                                flux,
                                (s, f) -> {
                                  RaceTestUtils.race(
                                      () ->
                                          f.subscribe(
                                              __ -> {},
                                              t -> {
                                                throwables[0] = t;
                                                latch.countDown();
                                              },
                                              latch::countDown),
                                      () ->
                                          f.subscribe(
                                              __ -> {},
                                              t -> {
                                                throwables[0] = t;
                                                latch.countDown();
                                              },
                                              latch::countDown));

                                  return Flux.empty();
                                },
                                false)
                            .filter(e -> true)))
        .expectSubscription()
        .expectComplete()
        .verify();

    Assertions.assertThat(throwables[0])
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("FluxSwitchOnFirst allows only one Subscriber");
  }

  @Test
  public void shouldNotSubscribeTwiceWhenCanceled() {
    CountDownLatch latch = new CountDownLatch(1);
    StepVerifier.create(
            Flux.just(1L, 2L)
                .doOnComplete(
                    () -> {
                      try {
                        latch.await();
                      } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                      }
                    })
                .hide()
                .publishOn(Schedulers.parallel())
                .cancelOn(NoOpsScheduler.INSTANCE)
                .doOnCancel(latch::countDown)
                .transform(flux -> new FluxSwitchOnFirst<>(flux, (s, f) -> f, false))
                .doOnSubscribe(
                    s -> Schedulers.single().schedule(s::cancel, 10, TimeUnit.MILLISECONDS)))
        .expectSubscription()
        .expectNext(2L)
        .expectNoEvent(Duration.ofMillis(200))
        .thenCancel()
        .verifyThenAssertThat()
        .hasNotDroppedErrors();
  }

  @Test
  public void shouldNotSubscribeTwiceConditionalWhenCanceled() {
    CountDownLatch latch = new CountDownLatch(1);
    StepVerifier.create(
            Flux.just(1L, 2L)
                .doOnComplete(
                    () -> {
                      try {
                        latch.await();
                      } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                      }
                    })
                .hide()
                .publishOn(Schedulers.parallel())
                .cancelOn(NoOpsScheduler.INSTANCE)
                .doOnCancel(latch::countDown)
                .transform(flux -> new FluxSwitchOnFirst<>(flux, (s, f) -> f, false))
                .filter(e -> true)
                .doOnSubscribe(
                    s -> Schedulers.single().schedule(s::cancel, 10, TimeUnit.MILLISECONDS)))
        .expectSubscription()
        .expectNext(2L)
        .expectNoEvent(Duration.ofMillis(200))
        .thenCancel()
        .verifyThenAssertThat()
        .hasNotDroppedErrors();
  }

  @Test
  public void shouldSendOnErrorSignalConditional() {
    @SuppressWarnings("unchecked")
    Signal<? extends Long>[] first = new Signal[1];

    RuntimeException error = new RuntimeException();
    StepVerifier.create(
            Flux.<Long>error(error)
                .transform(
                    flux ->
                        new FluxSwitchOnFirst<>(
                            flux,
                            (s, f) -> {
                              first[0] = s;

                              return f;
                            },
                            false))
                .filter(e -> true))
        .expectSubscription()
        .expectError(RuntimeException.class)
        .verify();

    Assertions.assertThat(first).containsExactly(Signal.error(error));
  }

  @Test
  public void shouldSendOnNextSignalConditional() {
    @SuppressWarnings("unchecked")
    Signal<? extends Long>[] first = new Signal[1];

    StepVerifier.create(
            Flux.just(1L)
                .transform(
                    flux ->
                        new FluxSwitchOnFirst<>(
                            flux,
                            (s, f) -> {
                              first[0] = s;

                              return f;
                            },
                            false))
                .filter(e -> true))
        .expectSubscription()
        .expectComplete()
        .verify();

    Assertions.assertThat((long) first[0].get()).isEqualTo(1L);
  }

  @Test
  public void shouldSendOnErrorSignalWithDelaySubscription() {
    @SuppressWarnings("unchecked")
    Signal<? extends Long>[] first = new Signal[1];

    RuntimeException error = new RuntimeException();
    StepVerifier.create(
            Flux.<Long>error(error)
                .transform(
                    flux ->
                        new FluxSwitchOnFirst<>(
                            flux,
                            (s, f) -> {
                              first[0] = s;

                              return f.delaySubscription(Duration.ofMillis(100));
                            },
                            false)))
        .expectSubscription()
        .expectError(RuntimeException.class)
        .verify();

    Assertions.assertThat(first).containsExactly(Signal.error(error));
  }

  @Test
  public void shouldSendOnCompleteSignalWithDelaySubscription() {
    @SuppressWarnings("unchecked")
    Signal<? extends Long>[] first = new Signal[1];

    StepVerifier.create(
            Flux.<Long>empty()
                .transform(
                    flux ->
                        new FluxSwitchOnFirst<>(
                            flux,
                            (s, f) -> {
                              first[0] = s;

                              return f.delaySubscription(Duration.ofMillis(100));
                            },
                            false)))
        .expectSubscription()
        .expectComplete()
        .verify();

    Assertions.assertThat(first).containsExactly(Signal.complete());
  }

  @Test
  public void shouldSendOnErrorSignal() {
    @SuppressWarnings("unchecked")
    Signal<? extends Long>[] first = new Signal[1];

    RuntimeException error = new RuntimeException();
    StepVerifier.create(
            Flux.<Long>error(error)
                .transform(
                    flux ->
                        new FluxSwitchOnFirst<>(
                            flux,
                            (s, f) -> {
                              first[0] = s;

                              return f;
                            },
                            false)))
        .expectSubscription()
        .expectError(RuntimeException.class)
        .verify();

    Assertions.assertThat(first).containsExactly(Signal.error(error));
  }

  @Test
  public void shouldSendOnNextSignal() {
    @SuppressWarnings("unchecked")
    Signal<? extends Long>[] first = new Signal[1];

    StepVerifier.create(
            Flux.just(1L)
                .transform(
                    flux ->
                        new FluxSwitchOnFirst<>(
                            flux,
                            (s, f) -> {
                              first[0] = s;

                              return f;
                            },
                            false)))
        .expectSubscription()
        .expectComplete()
        .verify();

    Assertions.assertThat((long) first[0].get()).isEqualTo(1L);
  }

  @Test
  public void shouldSendOnNextAsyncSignal() {
    @SuppressWarnings("unchecked")
    Signal<? extends Long>[] first = new Signal[1];

    StepVerifier.create(
            Flux.just(1L)
                .transform(
                    flux ->
                        new FluxSwitchOnFirst<>(
                            flux,
                            (s, f) -> {
                              first[0] = s;

                              return f.subscribeOn(Schedulers.elastic());
                            },
                            false)))
        .expectSubscription()
        .expectComplete()
        .verify();

    Assertions.assertThat((long) first[0].get()).isEqualTo(1L);
  }

  @Test
  public void shouldSendOnNextAsyncSignalConditional() {
    @SuppressWarnings("unchecked")
    Signal<? extends Long>[] first = new Signal[1];

    StepVerifier.create(
            Flux.just(1L)
                .transform(
                    flux ->
                        new FluxSwitchOnFirst<>(
                            flux,
                            (s, f) -> {
                              first[0] = s;

                              return f.subscribeOn(Schedulers.elastic());
                            },
                            false))
                .filter(p -> true))
        .expectSubscription()
        .expectComplete()
        .verify();

    Assertions.assertThat((long) first[0].get()).isEqualTo(1L);
  }

  @Test
  public void shouldNeverSendIncorrectRequestSizeToUpstream() throws InterruptedException {
    TestPublisher<Long> publisher = TestPublisher.createCold();
    AtomicLong capture = new AtomicLong(-1);
    ArrayList<Long> requested = new ArrayList<>();
    CountDownLatch latch = new CountDownLatch(1);
    Flux<Long> switchTransformed =
        publisher
            .flux()
            .doOnRequest(requested::add)
            .transform(
                flux -> new FluxSwitchOnFirst<>(flux, (first, innerFlux) -> innerFlux, false));

    publisher.next(1L);
    publisher.complete();

    switchTransformed.subscribe(capture::set, __ -> {}, latch::countDown, s -> s.request(1));

    latch.await(5, TimeUnit.SECONDS);

    Assertions.assertThat(capture.get()).isEqualTo(-1);
    Assertions.assertThat(requested).containsExactly(1L, 1L);
  }

  @Test
  public void shouldNeverSendIncorrectRequestSizeToUpstreamConditional()
      throws InterruptedException {
    TestPublisher<Long> publisher = TestPublisher.createCold();
    AtomicLong capture = new AtomicLong(-1);
    ArrayList<Long> requested = new ArrayList<>();
    CountDownLatch latch = new CountDownLatch(1);
    Flux<Long> switchTransformed =
        publisher
            .flux()
            .doOnRequest(e1 -> requested.add(e1))
            .transform(
                flux -> new FluxSwitchOnFirst<>(flux, (first, innerFlux) -> innerFlux, false))
            .filter(e -> true);

    publisher.next(1L);
    publisher.complete();

    switchTransformed.subscribe(capture::set, __ -> {}, latch::countDown, s -> s.request(1));

    latch.await(5, TimeUnit.SECONDS);

    Assertions.assertThat(capture.get()).isEqualTo(-1L);
    Assertions.assertThat(requested).containsExactly(1L, 1L);
  }

  @Test
  public void shouldBeRequestedOneFromUpstreamTwiceInCaseOfConditional()
      throws InterruptedException {
    TestPublisher<Long> publisher = TestPublisher.createCold();
    ArrayList<Long> capture = new ArrayList<>();
    ArrayList<Long> requested = new ArrayList<>();
    CountDownLatch latch = new CountDownLatch(1);
    Flux<Long> switchTransformed =
        publisher
            .flux()
            .doOnRequest(requested::add)
            .transform(
                flux -> new FluxSwitchOnFirst<>(flux, (first, innerFlux) -> innerFlux, false))
            .filter(e -> false);

    publisher.next(1L);
    publisher.complete();

    switchTransformed.subscribe(capture::add, __ -> {}, latch::countDown, s -> s.request(1));

    latch.await(5, TimeUnit.SECONDS);

    Assertions.assertThat(capture).isEmpty();
    Assertions.assertThat(requested).containsExactly(1L, 1L);
  }

  @Test
  public void shouldBeRequestedExactlyOneAndThenLongMaxValue() throws InterruptedException {
    TestPublisher<Long> publisher = TestPublisher.createCold();
    ArrayList<Long> capture = new ArrayList<>();
    ArrayList<Long> requested = new ArrayList<>();
    CountDownLatch latch = new CountDownLatch(1);
    Flux<Long> switchTransformed =
        publisher
            .flux()
            .doOnRequest(requested::add)
            .transform(
                flux -> new FluxSwitchOnFirst<>(flux, (first, innerFlux) -> innerFlux, false));

    publisher.next(1L);
    publisher.complete();

    switchTransformed.subscribe(capture::add, __ -> {}, latch::countDown);

    latch.await(5, TimeUnit.SECONDS);

    Assertions.assertThat(capture).isEmpty();
    Assertions.assertThat(requested).containsExactly(1L, Long.MAX_VALUE);
  }

  @Test
  public void shouldBeRequestedExactlyOneAndThenLongMaxValueConditional()
      throws InterruptedException {
    TestPublisher<Long> publisher = TestPublisher.createCold();
    ArrayList<Long> capture = new ArrayList<>();
    ArrayList<Long> requested = new ArrayList<>();
    CountDownLatch latch = new CountDownLatch(1);
    Flux<Long> switchTransformed =
        publisher
            .flux()
            .doOnRequest(requested::add)
            .transform(
                flux -> new FluxSwitchOnFirst<>(flux, (first, innerFlux) -> innerFlux, false));

    publisher.next(1L);
    publisher.complete();

    switchTransformed.subscribe(capture::add, __ -> {}, latch::countDown);

    latch.await(5, TimeUnit.SECONDS);

    Assertions.assertThat(capture).isEmpty();
    Assertions.assertThat(requested).containsExactly(1L, Long.MAX_VALUE);
  }

  @Test
  public void shouldReturnCorrectContextOnEmptySource() {
    @SuppressWarnings("unchecked")
    Signal<? extends Long>[] first = new Signal[1];

    Flux<Long> switchTransformed =
        Flux.<Long>empty()
            .transform(
                flux ->
                    new FluxSwitchOnFirst<>(
                        flux,
                        (f, innerFlux) -> {
                          first[0] = f;
                          return innerFlux;
                        },
                        false))
            .subscriberContext(Context.of("a", "c"))
            .subscriberContext(Context.of("c", "d"));

    StepVerifier.create(switchTransformed, 0)
        .expectSubscription()
        .thenRequest(1)
        .expectAccessibleContext()
        .contains("a", "c")
        .contains("c", "d")
        .then()
        .expectComplete()
        .verify();

    Assertions.assertThat(first)
        .containsExactly(Signal.complete(Context.of("a", "c").put("c", "d")));
  }

  @Test
  public void shouldNotFailOnIncorrectPublisherBehavior() {
    TestPublisher<Long> publisher =
        TestPublisher.createNoncompliant(TestPublisher.Violation.CLEANUP_ON_TERMINATE);
    Flux<Long> switchTransformed =
        publisher
            .flux()
            .transform(
                flux ->
                    new FluxSwitchOnFirst<>(
                        flux,
                        (first, innerFlux) -> innerFlux.subscriberContext(Context.of("a", "b")),
                        false));

    StepVerifier.create(
            new Flux<Long>() {
              @Override
              public void subscribe(CoreSubscriber<? super Long> actual) {
                switchTransformed.subscribe(actual);
                publisher.next(1L);
              }
            },
            0)
        .thenRequest(1)
        .then(() -> publisher.next(2L))
        .expectNext(2L)
        .then(() -> publisher.error(new RuntimeException()))
        .then(() -> publisher.error(new RuntimeException()))
        .then(() -> publisher.error(new RuntimeException()))
        .then(() -> publisher.error(new RuntimeException()))
        .expectError()
        .verifyThenAssertThat()
        .hasDroppedErrors(3)
        .tookLessThan(Duration.ofSeconds(10));

    publisher.assertWasRequested();
    publisher.assertNoRequestOverflow();
  }

  @Test
  public void shouldBeAbleToAccessUpstreamContext() {
    TestPublisher<Long> publisher = TestPublisher.createCold();

    Flux<String> switchTransformed =
        publisher
            .flux()
            .transform(
                flux ->
                    new FluxSwitchOnFirst<>(
                        flux,
                        (first, innerFlux) ->
                            innerFlux.map(String::valueOf).subscriberContext(Context.of("a", "b")),
                        false))
            .subscriberContext(Context.of("a", "c"))
            .subscriberContext(Context.of("c", "d"));

    publisher.next(1L);
    publisher.next(2L);

    StepVerifier.create(switchTransformed, 0)
        .thenRequest(1)
        .expectNext("2")
        .thenRequest(1)
        .then(() -> publisher.next(3L))
        .expectNext("3")
        .expectAccessibleContext()
        .contains("a", "b")
        .contains("c", "d")
        .then()
        .then(publisher::complete)
        .expectComplete()
        .verify(Duration.ofSeconds(10));

    publisher.assertWasRequested();
    publisher.assertNoRequestOverflow();
  }

  @Test
  public void shouldNotHangWhenOneElementUpstream() {
    TestPublisher<Long> publisher = TestPublisher.createCold();

    Flux<String> switchTransformed =
        publisher
            .flux()
            .transform(
                flux ->
                    new FluxSwitchOnFirst<>(
                        flux,
                        (first, innerFlux) ->
                            innerFlux.map(String::valueOf).subscriberContext(Context.of("a", "b")),
                        false))
            .subscriberContext(Context.of("a", "c"))
            .subscriberContext(Context.of("c", "d"));

    publisher.next(1L);
    publisher.complete();

    StepVerifier.create(switchTransformed, 0).expectComplete().verify(Duration.ofSeconds(10));

    publisher.assertWasRequested();
    publisher.assertNoRequestOverflow();
  }

  @Test
  public void backpressureTest() {
    TestPublisher<Long> publisher = TestPublisher.createCold();
    AtomicLong requested = new AtomicLong();

    Flux<String> switchTransformed =
        publisher
            .flux()
            .doOnRequest(requested::addAndGet)
            .transform(
                flux ->
                    new FluxSwitchOnFirst<>(
                        flux, (first, innerFlux) -> innerFlux.map(String::valueOf), false));

    publisher.next(1L);

    StepVerifier.create(switchTransformed, 0)
        .thenRequest(1)
        .then(() -> publisher.next(2L))
        .expectNext("2")
        .then(publisher::complete)
        .expectComplete()
        .verify(Duration.ofSeconds(10));

    publisher.assertWasRequested();
    publisher.assertNoRequestOverflow();

    Assertions.assertThat(requested.get()).isEqualTo(2L);
  }

  @Test
  public void backpressureConditionalTest() {
    Flux<Integer> publisher = Flux.range(0, 10000);
    AtomicLong requested = new AtomicLong();

    Flux<String> switchTransformed =
        publisher
            .doOnRequest(requested::addAndGet)
            .transform(
                flux ->
                    new FluxSwitchOnFirst<>(
                        flux, (first, innerFlux) -> innerFlux.map(String::valueOf), false))
            .filter(e -> false);

    StepVerifier.create(switchTransformed, 0)
        .thenRequest(1)
        .expectComplete()
        .verify(Duration.ofSeconds(10));

    Assertions.assertThat(requested.get()).isEqualTo(2L);
  }

  @Test
  public void backpressureHiddenConditionalTest() {
    Flux<Integer> publisher = Flux.range(0, 10000);
    AtomicLong requested = new AtomicLong();

    Flux<String> switchTransformed =
        publisher
            .doOnRequest(requested::addAndGet)
            .transform(
                flux ->
                    new FluxSwitchOnFirst<>(
                        flux, (first, innerFlux) -> innerFlux.map(String::valueOf).hide(), false))
            .filter(e -> false);

    StepVerifier.create(switchTransformed, 0)
        .thenRequest(1)
        .expectComplete()
        .verify(Duration.ofSeconds(10));

    Assertions.assertThat(requested.get()).isEqualTo(10001L);
  }

  @Test
  public void backpressureDrawbackOnConditionalInTransformTest() {
    Flux<Integer> publisher = Flux.range(0, 10000);
    AtomicLong requested = new AtomicLong();

    Flux<String> switchTransformed =
        publisher
            .doOnRequest(requested::addAndGet)
            .transform(
                flux ->
                    new FluxSwitchOnFirst<>(
                        flux,
                        (first, innerFlux) -> innerFlux.map(String::valueOf).filter(e -> false),
                        false));

    StepVerifier.create(switchTransformed, 0)
        .thenRequest(1)
        .expectComplete()
        .verify(Duration.ofSeconds(10));

    Assertions.assertThat(requested.get()).isEqualTo(10001L);
  }

  @Test
  public void shouldErrorOnOverflowTest() {
    TestPublisher<Long> publisher = TestPublisher.createCold();

    Flux<String> switchTransformed =
        publisher
            .flux()
            .transform(
                flux ->
                    new FluxSwitchOnFirst<>(
                        flux, (first, innerFlux) -> innerFlux.map(String::valueOf), false));

    publisher.next(1L);
    publisher.next(2L);

    StepVerifier.create(switchTransformed, 0)
        .thenRequest(1)
        .expectNext("2")
        .then(() -> publisher.next(2L))
        .expectErrorSatisfies(
            t ->
                Assertions.assertThat(t)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Can't deliver value due to lack of requests"))
        .verify(Duration.ofSeconds(10));

    publisher.assertWasRequested();
    publisher.assertNoRequestOverflow();
  }

  @Test
  public void shouldPropagateonCompleteCorrectly() {
    Flux<String> switchTransformed =
        Flux.empty()
            .transform(
                flux ->
                    new FluxSwitchOnFirst<>(
                        flux, (first, innerFlux) -> innerFlux.map(String::valueOf), false));

    StepVerifier.create(switchTransformed).expectComplete().verify(Duration.ofSeconds(10));
  }

  @Test
  public void shouldPropagateOnCompleteWithMergedElementsCorrectly() {
    Flux<String> switchTransformed =
        Flux.empty()
            .transform(
                flux ->
                    new FluxSwitchOnFirst<>(
                        flux,
                        (first, innerFlux) ->
                            innerFlux.map(String::valueOf).mergeWith(Flux.just("1", "2", "3")),
                        false));

    StepVerifier.create(switchTransformed)
        .expectNext("1", "2", "3")
        .expectComplete()
        .verify(Duration.ofSeconds(10));
  }

  @Test
  public void shouldPropagateErrorCorrectly() {
    Flux<String> switchTransformed =
        Flux.error(new RuntimeException("hello"))
            .transform(
                flux ->
                    new FluxSwitchOnFirst<>(
                        flux, (first, innerFlux) -> innerFlux.map(String::valueOf), true));

    StepVerifier.create(switchTransformed)
        .expectErrorMessage("hello")
        .verify(Duration.ofSeconds(10));
  }

  @Test
  public void shouldBeAbleToBeCancelledProperly() {
    TestPublisher<Integer> publisher = TestPublisher.createCold();
    Flux<String> switchTransformed =
        publisher
            .flux()
            .transform(
                flux ->
                    new FluxSwitchOnFirst<>(
                        flux, (first, innerFlux) -> innerFlux.map(String::valueOf), false));

    publisher.next(1);

    StepVerifier.create(switchTransformed, 0).thenCancel().verify(Duration.ofSeconds(10));

    publisher.assertCancelled();
    publisher.assertWasRequested();
  }

  @Test
  public void shouldBeAbleToBeCancelledProperly2() {
    TestPublisher<Integer> publisher = TestPublisher.createCold();
    Flux<String> switchTransformed =
        publisher
            .flux()
            .transform(
                flux ->
                    new FluxSwitchOnFirst<>(
                        flux, (first, innerFlux) -> innerFlux.map(String::valueOf).take(1), false));

    publisher.next(1);
    publisher.next(2);
    publisher.next(3);
    publisher.next(4);

    StepVerifier.create(switchTransformed, 1)
        .expectNext("2")
        .expectComplete()
        .verify(Duration.ofSeconds(10));

    publisher.assertCancelled();
    publisher.assertWasRequested();
  }

  @Test
  public void shouldBeAbleToBeCancelledProperly3() {
    TestPublisher<Integer> publisher = TestPublisher.createCold();
    Flux<String> switchTransformed =
        publisher
            .flux()
            .transform(
                flux ->
                    new FluxSwitchOnFirst<>(
                        flux, (first, innerFlux) -> innerFlux.map(String::valueOf), false))
            .take(1);

    publisher.next(1);
    publisher.next(2);
    publisher.next(3);
    publisher.next(4);

    StepVerifier.create(switchTransformed, 1)
        .expectNext("2")
        .expectComplete()
        .verify(Duration.ofSeconds(10));

    publisher.assertCancelled();
    publisher.assertWasRequested();
  }

  @Test
  public void shouldReturnNormallyIfExceptionIsThrownOnNextDuringSwitching() {
    @SuppressWarnings("unchecked")
    Signal<? extends Long>[] first = new Signal[1];

    Optional<?> expectedCause = Optional.of(1L);

    StepVerifier.create(
            Flux.just(1L)
                .transform(
                    flux ->
                        new FluxSwitchOnFirst<>(
                            flux,
                            (s, f) -> {
                              first[0] = s;
                              throw new NullPointerException();
                            },
                            false)))
        .expectSubscription()
        .expectError(NullPointerException.class)
        .verifyThenAssertThat()
        .hasOperatorErrorsSatisfying(
            c ->
                Assertions.assertThat(c)
                    .hasOnlyOneElementSatisfying(
                        t -> {
                          Assertions.assertThat(t.getT1())
                              .containsInstanceOf(NullPointerException.class);
                          Assertions.assertThat(t.getT2()).isEqualTo(expectedCause);
                        }));

    Assertions.assertThat((long) first[0].get()).isEqualTo(1L);
  }

  @Test
  public void shouldReturnNormallyIfExceptionIsThrownOnErrorDuringSwitching() {
    @SuppressWarnings("unchecked")
    Signal<? extends Long>[] first = new Signal[1];

    NullPointerException npe = new NullPointerException();
    RuntimeException error = new RuntimeException();
    StepVerifier.create(
            Flux.<Long>error(error)
                .transform(
                    flux ->
                        new FluxSwitchOnFirst<>(
                            flux,
                            (s, f) -> {
                              first[0] = s;
                              throw npe;
                            },
                            false)))
        .expectSubscription()
        .verifyError(NullPointerException.class);

    Assertions.assertThat(first).containsExactly(Signal.error(error));
  }

  @Test
  public void shouldReturnNormallyIfExceptionIsThrownOnCompleteDuringSwitching() {
    @SuppressWarnings("unchecked")
    Signal<? extends Long>[] first = new Signal[1];

    StepVerifier.create(
            Flux.<Long>empty()
                .transform(
                    flux ->
                        new FluxSwitchOnFirst<>(
                            flux,
                            (s, f) -> {
                              first[0] = s;
                              throw new NullPointerException();
                            },
                            false)))
        .expectSubscription()
        .expectError(NullPointerException.class)
        .verifyThenAssertThat()
        .hasOperatorErrorMatching(
            t -> {
              Assertions.assertThat(t).isInstanceOf(NullPointerException.class);
              return true;
            });

    Assertions.assertThat(first).containsExactly(Signal.complete());
  }

  @Test
  public void sourceSubscribedOnce() {
    AtomicInteger subCount = new AtomicInteger();
    Flux<Integer> source =
        Flux.range(1, 10).hide().doOnSubscribe(subscription -> subCount.incrementAndGet());

    StepVerifier.create(
            source.transform(
                flux ->
                    new FluxSwitchOnFirst<>(
                        flux, (s, f) -> f.filter(v -> v % 2 == s.get()), false)))
        .expectNext(3, 5, 7, 9)
        .verifyComplete();

    Assertions.assertThat(subCount).hasValue(1);
  }

  @Test
  public void checkHotSource() {
    ReplayProcessor<Long> processor = ReplayProcessor.create(1);

    processor.onNext(1L);
    processor.onNext(2L);
    processor.onNext(3L);

    StepVerifier.create(
            processor.transform(
                flux ->
                    new FluxSwitchOnFirst<>(
                        flux, (s, f) -> f.filter(v -> v % s.get() == 0), false)))
        .then(
            () -> {
              processor.onNext(4L);
              processor.onNext(5L);
              processor.onNext(6L);
              processor.onNext(7L);
              processor.onNext(8L);
              processor.onNext(9L);
              processor.onComplete();
            })
        .expectNext(6L, 9L)
        .verifyComplete();
  }

  @Test
  public void shouldCancelSourceOnUnrelatedPublisherComplete() {
    EmitterProcessor<Long> testPublisher = EmitterProcessor.create();

    testPublisher.onNext(1L);

    StepVerifier.create(
            testPublisher.transform(
                flux -> new FluxSwitchOnFirst<>(flux, (s, f) -> Flux.empty(), true)))
        .expectSubscription()
        .verifyComplete();

    Assertions.assertThat(testPublisher.isCancelled()).isTrue();
  }

  @Test
  public void shouldCancelSourceOnUnrelatedPublisherError() {
    EmitterProcessor<Long> testPublisher = EmitterProcessor.create();

    testPublisher.onNext(1L);

    StepVerifier.create(
            testPublisher.transform(
                flux ->
                    new FluxSwitchOnFirst<>(
                        flux, (s, f) -> Flux.error(new RuntimeException("test")), false)))
        .expectSubscription()
        .verifyErrorSatisfies(
            t ->
                Assertions.assertThat(t)
                    .hasMessage("test")
                    .isExactlyInstanceOf(RuntimeException.class));

    Assertions.assertThat(testPublisher.isCancelled()).isTrue();
  }

  @Test
  public void shouldCancelSourceOnUnrelatedPublisherCancel() {
    TestPublisher<Long> testPublisher = TestPublisher.create();

    StepVerifier.create(
            testPublisher
                .flux()
                .transform(
                    flux ->
                        new FluxSwitchOnFirst<>(
                            flux, (s, f) -> Flux.error(new RuntimeException("test")), false)))
        .expectSubscription()
        .thenCancel()
        .verify();

    Assertions.assertThat(testPublisher.wasCancelled()).isTrue();
  }

  @Test
  public void shouldCancelUpstreamBeforeFirst() {
    EmitterProcessor<Long> testPublisher = EmitterProcessor.create();

    StepVerifier.create(
            testPublisher.transform(
                flux ->
                    new FluxSwitchOnFirst<>(
                        flux, (s, f) -> Flux.error(new RuntimeException("test")), false)))
        .thenAwait(Duration.ofMillis(50))
        .thenCancel()
        .verify(Duration.ofSeconds(2));

    Assertions.assertThat(testPublisher.isCancelled()).isTrue();
  }

  @Test
  public void shouldContinueWorkingRegardlessTerminalOnDownstream() {
    TestPublisher<Long> testPublisher = TestPublisher.create();

    Flux<Long>[] intercepted = new Flux[1];

    StepVerifier.create(
            testPublisher
                .flux()
                .transform(
                    flux ->
                        new FluxSwitchOnFirst<>(
                            flux,
                            (s, f) -> {
                              intercepted[0] = f;
                              return Flux.just(2L);
                            },
                            false)))
        .expectSubscription()
        .then(() -> testPublisher.next(1L))
        .expectNext(2L)
        .expectComplete()
        .verify(Duration.ofSeconds(2));

    Assertions.assertThat(testPublisher.wasCancelled()).isFalse();

    StepVerifier.create(intercepted[0])
        .expectSubscription()
        .then(testPublisher::complete)
        .expectComplete()
        .verify(Duration.ofSeconds(1));
  }

  @Test
  public void shouldCancelSourceOnOnDownstreamTerminal() {
    TestPublisher<Long> testPublisher = TestPublisher.create();

    StepVerifier.create(
            testPublisher
                .flux()
                .transform(flux -> new FluxSwitchOnFirst<>(flux, (s, f) -> Flux.just(1L), true)))
        .expectSubscription()
        .then(() -> testPublisher.next(1L))
        .expectNext(1L)
        .expectComplete()
        .verify(Duration.ofSeconds(2));

    Assertions.assertThat(testPublisher.wasCancelled()).isTrue();
  }

  @Test
  public void racingTest() {
    for (int i = 0; i < 1000; i++) {
      CoreSubscriber[] subscribers = new CoreSubscriber[1];
      Subscription[] downstreamSubscriptions = new Subscription[1];
      Subscription[] innerSubscriptions = new Subscription[1];

      AtomicLong requested = new AtomicLong();

      Flux.just(2)
          .doOnRequest(requested::addAndGet)
          .transform(
              flux ->
                  new FluxSwitchOnFirst<>(
                      flux,
                      (s, f) ->
                          new Flux<Integer>() {

                            @Override
                            public void subscribe(CoreSubscriber actual) {
                              subscribers[0] = actual;
                              f.subscribe(
                                  actual::onNext,
                                  actual::onError,
                                  actual::onComplete,
                                  (s) -> innerSubscriptions[0] = s);
                            }
                          },
                      false))
          .subscribe(__ -> {}, __ -> {}, () -> {}, s -> downstreamSubscriptions[0] = s);

      CoreSubscriber subscriber = subscribers[0];
      Subscription downstreamSubscription = downstreamSubscriptions[0];
      Subscription innerSubscription = innerSubscriptions[0];
      innerSubscription.request(1);

      RaceTestUtils.race(
          () -> subscriber.onSubscribe(innerSubscription), () -> downstreamSubscription.request(1));

      Assertions.assertThat(requested.get()).isEqualTo(3);
    }
  }

  @Test
  public void racingConditionalTest() {
    for (int i = 0; i < 1000; i++) {
      CoreSubscriber[] subscribers = new CoreSubscriber[1];
      Subscription[] downstreamSubscriptions = new Subscription[1];
      Subscription[] innerSubscriptions = new Subscription[1];

      AtomicLong requested = new AtomicLong();

      Flux.just(2)
          .doOnRequest(requested::addAndGet)
          .transform(
              flux ->
                  new FluxSwitchOnFirst<>(
                      flux,
                      (s, f) ->
                          new Flux<Integer>() {

                            @Override
                            public void subscribe(CoreSubscriber actual) {
                              subscribers[0] = actual;
                              f.subscribe(
                                  new Fuseable.ConditionalSubscriber<Integer>() {
                                    @Override
                                    public boolean tryOnNext(Integer integer) {
                                      return ((Fuseable.ConditionalSubscriber) actual)
                                          .tryOnNext(integer);
                                    }

                                    @Override
                                    public void onSubscribe(Subscription s) {
                                      innerSubscriptions[0] = s;
                                    }

                                    @Override
                                    public void onNext(Integer integer) {
                                      actual.onNext(integer);
                                    }

                                    @Override
                                    public void onError(Throwable throwable) {
                                      actual.onError(throwable);
                                    }

                                    @Override
                                    public void onComplete() {
                                      actual.onComplete();
                                    }
                                  });
                            }
                          },
                      false))
          .filter(__ -> true)
          .subscribe(__ -> {}, __ -> {}, () -> {}, s -> downstreamSubscriptions[0] = s);

      CoreSubscriber subscriber = subscribers[0];
      Subscription downstreamSubscription = downstreamSubscriptions[0];
      Subscription innerSubscription = innerSubscriptions[0];
      innerSubscription.request(1);

      RaceTestUtils.race(
          () -> subscriber.onSubscribe(innerSubscription), () -> downstreamSubscription.request(1));

      Assertions.assertThat(requested.get()).isEqualTo(3);
    }
  }

  private static final class NoOpsScheduler implements Scheduler {

    static final NoOpsScheduler INSTANCE = new NoOpsScheduler();

    private NoOpsScheduler() {}

    @Override
    public Disposable schedule(Runnable task) {
      return Disposables.composite();
    }

    @Override
    public Worker createWorker() {
      return NoOpsWorker.INSTANCE;
    }

    static final class NoOpsWorker implements Worker {

      static final NoOpsWorker INSTANCE = new NoOpsWorker();

      @Override
      public Disposable schedule(Runnable task) {
        return Disposables.never();
      }

      @Override
      public void dispose() {}
    };
  }
}
