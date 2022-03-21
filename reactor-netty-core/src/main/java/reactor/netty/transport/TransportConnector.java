/*
 * Copyright (c) 2020-2022 VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty.transport;

import io.micrometer.contextpropagation.ContextContainer;
import io.netty5.channel.Channel;
import io.netty5.channel.ChannelFactory;
import io.netty5.channel.ChannelInitializer;
import io.netty5.channel.ChannelOption;
import io.netty5.channel.EventLoop;
import io.netty5.channel.EventLoopGroup;
import io.netty5.channel.ServerChannelFactory;
import io.netty5.channel.unix.DomainSocketAddress;
import io.netty5.resolver.AddressResolver;
import io.netty5.resolver.AddressResolverGroup;
import io.netty5.util.AttributeKey;
import io.netty5.util.concurrent.EventExecutor;
import io.netty5.util.concurrent.Future;
import io.netty5.util.concurrent.Promise;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;
import reactor.util.retry.Retry;

import java.net.SocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static reactor.netty.ReactorNetty.format;

/**
 * {@link TransportConnector} is a helper class that creates, initializes and registers the channel.
 * It performs the actual connect operation to the remote peer or binds the channel.
 *
 * @author Stephane Maldini
 * @author Violeta Georgieva
 * @since 1.0.0
 */
public final class TransportConnector {

	TransportConnector() {}

	/**
	 * Binds a {@link Channel}.
	 *
	 * @param config the transport configuration
	 * @param channelInitializer the {@link ChannelInitializer} that will be used for initializing the channel pipeline
	 * @param bindAddress the local address
	 * @param isDomainSocket true if {@link io.netty5.channel.unix.DomainSocketChannel} or
	 * {@link io.netty5.channel.unix.ServerDomainSocketChannel} is needed, false otherwise
	 * @return a {@link Mono} of {@link Channel}
	 */
	@SuppressWarnings("FutureReturnValueIgnored")
	public static Mono<Channel> bind(TransportConfig config, ChannelInitializer<Channel> channelInitializer,
			SocketAddress bindAddress, boolean isDomainSocket) {
		Objects.requireNonNull(config, "config");
		Objects.requireNonNull(bindAddress, "bindAddress");
		Objects.requireNonNull(channelInitializer, "channelInitializer");

		return doInitAndRegister(config, channelInitializer, isDomainSocket, config.eventLoopGroup().next(), ContextContainer.NOOP)
				.flatMap(channel -> {
					MonoChannelPromise promise = new MonoChannelPromise(channel);
					// "FutureReturnValueIgnored" this is deliberate
					channel.executor().execute(() ->
							channel.bind(bindAddress)
									.addListener(f -> {
										if (f.isSuccess()) {
											promise.asPromise().setSuccess(null);
										}
										else {
											// "FutureReturnValueIgnored" this is deliberate
											channel.close();
											promise.asPromise().setFailure(f.cause());
										}
									}));
					return promise;
				});
	}

	/**
	 * Connect a {@link Channel} to the remote peer.
	 *
	 * @param config the transport configuration
	 * @param remoteAddress the {@link SocketAddress} to connect to
	 * @param resolverGroup the resolver which will resolve the address of the unresolved named address
	 * @param channelInitializer the {@link ChannelInitializer} that will be used for initializing the channel pipeline
	 * @return a {@link Mono} of {@link Channel}
	 */
	public static Mono<Channel> connect(TransportConfig config, SocketAddress remoteAddress,
			AddressResolverGroup<?> resolverGroup, ChannelInitializer<Channel> channelInitializer) {
		return connect(config, remoteAddress, resolverGroup, channelInitializer, config.eventLoopGroup().next(),
				ContextContainer.NOOP);
	}

	/**
	 * Connect a {@link Channel} to the remote peer.
	 *
	 * @param config the transport configuration
	 * @param remoteAddress the {@link SocketAddress} to connect to
	 * @param resolverGroup the resolver which will resolve the address of the unresolved named address
	 * @param channelInitializer the {@link ChannelInitializer} that will be used for initializing the channel pipeline
	 * @param container {@link ContextContainer} is a holder of values that are being propagated through various contexts
	 * @return a {@link Mono} of {@link Channel}
	 */
	public static Mono<Channel> connect(TransportConfig config, SocketAddress remoteAddress,
			AddressResolverGroup<?> resolverGroup, ChannelInitializer<Channel> channelInitializer, ContextContainer container) {
		return connect(config, remoteAddress, resolverGroup, channelInitializer, config.eventLoopGroup().next(), container);
	}

	/**
	 * Connect a {@link Channel} to the remote peer.
	 *
	 * @param config the transport configuration
	 * @param remoteAddress the {@link SocketAddress} to connect to
	 * @param resolverGroup the resolver which will resolve the address of the unresolved named address
	 * @param channelInitializer the {@link ChannelInitializer} that will be used for initializing the channel pipeline
	 * @param eventLoop the {@link EventLoop} to use for handling the channel.
	 * @return a {@link Mono} of {@link Channel}
	 */
	public static Mono<Channel> connect(TransportConfig config, SocketAddress remoteAddress,
			AddressResolverGroup<?> resolverGroup, ChannelInitializer<Channel> channelInitializer, EventLoop eventLoop) {
		return connect(config, remoteAddress, resolverGroup, channelInitializer, eventLoop, ContextContainer.NOOP);
	}

	/**
	 * Connect a {@link Channel} to the remote peer.
	 *
	 * @param config the transport configuration
	 * @param remoteAddress the {@link SocketAddress} to connect to
	 * @param resolverGroup the resolver which will resolve the address of the unresolved named address
	 * @param channelInitializer the {@link ChannelInitializer} that will be used for initializing the channel pipeline
	 * @param eventLoop the {@link EventLoop} to use for handling the channel.
	 * @param container {@link ContextContainer} is a holder of values that are being propagated through various contexts
	 * @return a {@link Mono} of {@link Channel}
	 */
	public static Mono<Channel> connect(TransportConfig config, SocketAddress remoteAddress,
			AddressResolverGroup<?> resolverGroup, ChannelInitializer<Channel> channelInitializer, EventLoop eventLoop,
			ContextContainer container) {
		Objects.requireNonNull(config, "config");
		Objects.requireNonNull(remoteAddress, "remoteAddress");
		Objects.requireNonNull(resolverGroup, "resolverGroup");
		Objects.requireNonNull(channelInitializer, "channelInitializer");
		Objects.requireNonNull(eventLoop, "eventLoop");
		Objects.requireNonNull(container, "container");

		boolean isDomainAddress = remoteAddress instanceof DomainSocketAddress;
		return doInitAndRegister(config, channelInitializer, isDomainAddress, eventLoop, container)
				.flatMap(channel -> doResolveAndConnect(channel, config, remoteAddress, resolverGroup, container)
						.onErrorResume(RetryConnectException.class,
								t -> {
									AtomicInteger index = new AtomicInteger(1);
									return Mono.defer(() ->
											doInitAndRegister(config, channelInitializer, isDomainAddress, eventLoop, container)
													.flatMap(ch -> {
														MonoChannelPromise mono = new MonoChannelPromise(ch);
														doConnect(t.addresses, config.bindAddress(), mono, index.get());
														return mono;
													}))
											.retryWhen(Retry.max(t.addresses.size() - 1)
															.filter(RETRY_PREDICATE)
															.doBeforeRetry(sig -> index.incrementAndGet()));
								}));
	}

	/**
	 * Set the channel attributes
	 *
	 * @param channel the channel
	 * @param attrs the attributes
	 */
	@SuppressWarnings("unchecked")
	static void setAttributes(Channel channel, Map<AttributeKey<?>, ?> attrs) {
		for (Map.Entry<AttributeKey<?>, ?> e : attrs.entrySet()) {
			channel.attr((AttributeKey<Object>) e.getKey()).set(e.getValue());
		}
	}

	/**
	 * Set the channel options
	 *
	 * @param channel the channel
	 * @param options the options
	 */
	@SuppressWarnings("unchecked")
	static void setChannelOptions(Channel channel, Map<ChannelOption<?>, ?> options, boolean isDomainSocket) {
		for (Map.Entry<ChannelOption<?>, ?> e : options.entrySet()) {
			if (isDomainSocket &&
					(ChannelOption.SO_REUSEADDR.equals(e.getKey()) || ChannelOption.TCP_NODELAY.equals(e.getKey()))) {
				continue;
			}
			try {
				if (!channel.config().setOption((ChannelOption<Object>) e.getKey(), e.getValue())) {
					log.warn(format(channel, "Unknown channel option '{}' for channel '{}'"), e.getKey(), channel);
				}
			}
			catch (Throwable t) {
				log.warn(format(channel, "Failed to set channel option '{}' with value '{}' for channel '{}'"),
						e.getKey(), e.getValue(), channel, t);
			}
		}
	}

	@SuppressWarnings("FutureReturnValueIgnored")
	static void doConnect(
			List<SocketAddress> addresses,
			@Nullable Supplier<? extends SocketAddress> bindAddress,
			MonoChannelPromise connectPromise,
			int index) {
		Channel channel = connectPromise.channel;
		channel.executor().execute(() -> {
			SocketAddress remoteAddress = addresses.get(index);

			if (log.isDebugEnabled()) {
				log.debug(format(channel, "Connecting to [" + remoteAddress + "]."));
			}

			Future<Void> f;
			if (bindAddress == null) {
				f = channel.connect(remoteAddress);
			}
			else {
				SocketAddress local = Objects.requireNonNull(bindAddress.get(), "bindAddress");
				f = channel.connect(remoteAddress, local);
			}

			f.addListener(future -> {
				if (future.isSuccess()) {
					connectPromise.asPromise().setSuccess(null);
				}
				else {
					// "FutureReturnValueIgnored" this is deliberate
					channel.close();

					Throwable cause = future.cause();
					if (log.isDebugEnabled()) {
						log.debug(format(channel, "Connect attempt to [" + remoteAddress + "] failed."), cause);
					}

					int next = index + 1;
					if (next < addresses.size()) {
						connectPromise.asPromise().setFailure(new RetryConnectException(addresses));
					}
					else {
						connectPromise.asPromise().setFailure(cause);
					}
				}
			});
		});
	}

	static Mono<Channel> doInitAndRegister(
			TransportConfig config,
			ChannelInitializer<Channel> channelInitializer,
			boolean isDomainSocket,
			EventLoop eventLoop,
			ContextContainer container) {
		boolean onServer = channelInitializer instanceof ServerTransport.AcceptorInitializer;
		Channel channel;
		try {
			if (onServer) {
				EventLoopGroup childEventLoopGroup = ((ServerTransportConfig) config).childEventLoopGroup();
				ServerChannelFactory<? extends Channel> channelFactory = config.serverConnectionFactory(isDomainSocket);
				channel = channelFactory.newChannel(eventLoop, childEventLoopGroup);
				((ServerTransport.AcceptorInitializer) channelInitializer).acceptor.enableAutoReadTask(channel);
			}
			else {
				ChannelFactory<? extends Channel> channelFactory = config.connectionFactory(isDomainSocket);
				channel = channelFactory.newChannel(eventLoop);
			}
		}
		catch (Throwable t) {
			return Mono.error(t);
		}

		container.save(channel);

		MonoChannelPromise monoChannelPromise = new MonoChannelPromise(channel);
		eventLoop.execute(() -> {
			// Init channel
			setChannelOptions(channel, config.options, isDomainSocket);
			setAttributes(channel, config.attrs);

			Future<Void> initFuture;
			if (onServer) {
				Promise<Void> promise = channel.newPromise();
				((ServerTransport.AcceptorInitializer) channelInitializer).initPromise = promise;
				channel.pipeline().addLast(channelInitializer);
				initFuture = promise.asFuture();
			}
			else {
				channel.pipeline().addLast(channelInitializer);
				initFuture = channel.newSucceededFuture();
			}

			initFuture.addListener(future -> {
				if (future.isSuccess()) {
					channel.register().addListener(f -> {
						if (f.isSuccess()) {
							monoChannelPromise.asPromise().setSuccess(null);
						}
						else {
							if (channel.isRegistered()) {
								// "FutureReturnValueIgnored" this is deliberate
								channel.close();
							}
							else {
								channel.unsafe().closeForcibly();
							}
							monoChannelPromise.asPromise().setFailure(f.cause());
						}
					});
				}
				else {
					channel.unsafe().closeForcibly();
					monoChannelPromise.asPromise().setFailure(future.cause());
				}
			});
		});

		return monoChannelPromise;
	}

	@SuppressWarnings({"unchecked", "FutureReturnValueIgnored", "try"})
	static Mono<Channel> doResolveAndConnect(Channel channel, TransportConfig config,
			SocketAddress remoteAddress, AddressResolverGroup<?> resolverGroup, ContextContainer container) {
		try {
			AddressResolver<SocketAddress> resolver =
					(AddressResolver<SocketAddress>) resolverGroup.getResolver(channel.executor());

			Supplier<? extends SocketAddress> bindAddress = config.bindAddress();
			if (!resolver.isSupported(remoteAddress) || resolver.isResolved(remoteAddress)) {
				MonoChannelPromise monoChannelPromise = new MonoChannelPromise(channel);
				doConnect(Collections.singletonList(remoteAddress), bindAddress, monoChannelPromise, 0);
				return monoChannelPromise;
			}

			if (config instanceof ClientTransportConfig) {
				final ClientTransportConfig<?> clientTransportConfig = (ClientTransportConfig<?>) config;
				if (clientTransportConfig.doOnResolve != null) {
					clientTransportConfig.doOnResolve.accept(Connection.from(channel));
				}
			}

			Future<List<SocketAddress>> resolveFuture;
			try (ContextContainer.Scope scope = container.restoreThreadLocalValues()) {
				resolveFuture = resolver.resolveAll(remoteAddress);
			}

			if (config instanceof ClientTransportConfig) {
				final ClientTransportConfig<?> clientTransportConfig = (ClientTransportConfig<?>) config;

				if (clientTransportConfig.doOnResolveError != null) {
					resolveFuture.addListener(future -> {
						if (future.cause() != null) {
							clientTransportConfig.doOnResolveError.accept(Connection.from(channel), future.cause());
						}
					});
				}

				if (clientTransportConfig.doAfterResolve != null) {
					resolveFuture.addListener(future -> {
						if (future.isSuccess()) {
							clientTransportConfig.doAfterResolve.accept(Connection.from(channel), future.getNow().get(0));
						}
					});
				}
			}

			if (resolveFuture.isDone()) {
				Throwable cause = resolveFuture.cause();
				if (cause != null) {
					// "FutureReturnValueIgnored" this is deliberate
					channel.close();
					return Mono.error(cause);
				}
				else {
					MonoChannelPromise monoChannelPromise = new MonoChannelPromise(channel);
					doConnect(resolveFuture.getNow(), bindAddress, monoChannelPromise, 0);
					return monoChannelPromise;
				}
			}

			MonoChannelPromise monoChannelPromise = new MonoChannelPromise(channel);
			resolveFuture.addListener(future -> {
				if (future.cause() != null) {
					// "FutureReturnValueIgnored" this is deliberate
					channel.close();
					monoChannelPromise.asPromise().tryFailure(future.cause());
				}
				else {
					doConnect(future.getNow(), bindAddress, monoChannelPromise, 0);
				}
			});
			return monoChannelPromise;
		}
		catch (Throwable t) {
			return Mono.error(t);
		}
	}

	static final class MonoChannelPromise extends Mono<Channel> implements Subscription {

		final Channel channel;
		final ChannelPromise channelPromise;

		CoreSubscriber<? super Channel> actual;

		MonoChannelPromise(Channel channel) {
			this.channel = channel;
			this.channelPromise = new ChannelPromise(channel);
		}

		@Override
		@SuppressWarnings("FutureReturnValueIgnored")
		public void cancel() {
			// "FutureReturnValueIgnored" this is deliberate
			channel.close();
		}

		@Override
		public void request(long n) {
			// noop
		}

		@Override
		public void subscribe(CoreSubscriber<? super Channel> actual) {
			EventLoop eventLoop = channel.executor();
			if (eventLoop.inEventLoop()) {
				_subscribe(actual);
			}
			else {
				eventLoop.execute(() -> _subscribe(actual));
			}
		}

		Promise<Void> asPromise() {
			return channelPromise;
		}

		void _subscribe(CoreSubscriber<? super Channel> actual) {
			this.actual = actual;
			channelPromise.actual = actual;
			actual.onSubscribe(this);

			if (channelPromise.isDone()) {
				if (channelPromise.isSuccess()) {
					actual.onNext(channel);
					actual.onComplete();
				}
				else {
					actual.onError(channelPromise.cause());
				}
			}
		}

		static final class ChannelPromise implements Promise<Void> {

			final Channel channel;

			CoreSubscriber<? super Channel> actual;

			ChannelPromise(Channel channel) {
				this.channel = channel;
			}

			@Override
			public Future<Void> asFuture() {
				throw new UnsupportedOperationException();
			}

			@Override
			@SuppressWarnings("FutureReturnValueIgnored")
			public boolean cancel() {
				// "FutureReturnValueIgnored" this is deliberate
				channel.close();
				return true;
			}

			@Override
			public Throwable cause() {
				Object result = this.result;
				return result == SUCCESS ? null : (Throwable) result;
			}

			@Override
			public EventExecutor executor() {
				return channel.executor();
			}

			@Override
			public Void getNow() {
				throw new UnsupportedOperationException();
			}

			@Override
			public boolean isCancellable() {
				return false;
			}

			@Override
			public boolean isCancelled() {
				return false;
			}

			@Override
			public boolean isDone() {
				Object result = this.result;
				return result != null;
			}

			@Override
			public boolean isFailed() {
				return !isSuccess();
			}

			@Override
			public boolean isSuccess() {
				Object result = this.result;
				return result == SUCCESS;
			}

			@Override
			public Promise<Void> setFailure(Throwable cause) {
				tryFailure(cause);
				return this;
			}

			@Override
			public Promise<Void> setSuccess(Void result) {
				trySuccess(result);
				return this;
			}

			@Override
			public boolean setUncancellable() {
				return true;
			}

			@Override
			public boolean tryFailure(Throwable cause) {
				if (RESULT_UPDATER.compareAndSet(this, null, cause)) {
					if (actual != null) {
						actual.onError(cause);
					}
					return true;
				}
				return false;
			}

			@Override
			public boolean trySuccess(Void result) {
				if (RESULT_UPDATER.compareAndSet(this, null, SUCCESS)) {
					if (actual != null) {
						actual.onNext(channel);
						actual.onComplete();
					}
					return true;
				}
				return false;
			}

			static final Object SUCCESS = new Object();
			static final AtomicReferenceFieldUpdater<ChannelPromise, Object> RESULT_UPDATER =
					AtomicReferenceFieldUpdater.newUpdater(ChannelPromise.class, Object.class, "result");
			volatile Object result;
		}
	}

	static final class RetryConnectException extends RuntimeException {

		final List<SocketAddress> addresses;

		RetryConnectException(List<SocketAddress> addresses) {
			this.addresses = addresses;
		}

		@Override
		public synchronized Throwable fillInStackTrace() {
			// omit stacktrace for this exception
			return this;
		}

		private static final long serialVersionUID = -207274323623692199L;
	}

	static final Logger log = Loggers.getLogger(TransportConnector.class);

	static final Predicate<Throwable> RETRY_PREDICATE = t -> t instanceof RetryConnectException;
}
