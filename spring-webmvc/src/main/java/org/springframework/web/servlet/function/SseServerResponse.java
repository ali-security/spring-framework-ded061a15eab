/*
 * Copyright 2002-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.web.servlet.function;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.DelegatingServerHttpResponse;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.servlet.ModelAndView;

/**
 * Implementation of {@link ServerResponse} for sending
 * <a href="https://www.w3.org/TR/eventsource/">Server-Sent Events</a>.
 *
 * @author Arjen Poutsma
 * @author Sebastien Deleuze
 * @since 5.3.2
 */
final class SseServerResponse extends AbstractServerResponse {

	private final Consumer<SseBuilder> sseConsumer;

	@Nullable
	private final Duration timeout;


	private SseServerResponse(Consumer<SseBuilder> sseConsumer, @Nullable Duration timeout) {
		super(HttpStatus.OK, createHeaders(), emptyCookies());
		this.sseConsumer = sseConsumer;
		this.timeout = timeout;
	}

	private static HttpHeaders createHeaders() {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.TEXT_EVENT_STREAM);
		headers.setCacheControl(CacheControl.noCache());
		return headers;
	}

	private static MultiValueMap<String, Cookie> emptyCookies() {
		return CollectionUtils.toMultiValueMap(Collections.emptyMap());
	}


	@Nullable
	@Override
	protected ModelAndView writeToInternal(HttpServletRequest request, HttpServletResponse response,
			Context context) throws ServletException, IOException {

		DeferredResult<?> result;
		if (this.timeout != null) {
			result = new DeferredResult<>(this.timeout.toMillis());
		}
		else {
			result = new DeferredResult<>();
		}

		DefaultAsyncServerResponse.writeAsync(request, response, result);
		this.sseConsumer.accept(new DefaultSseBuilder(response, context, result, this.headers()));
		return null;
	}


	public static ServerResponse create(Consumer<SseBuilder> sseConsumer, @Nullable Duration timeout) {
		Assert.notNull(sseConsumer, "SseConsumer must not be null");

		return new SseServerResponse(sseConsumer, timeout);
	}


	private static final class DefaultSseBuilder implements SseBuilder {

		private static final byte[] NL_NL = new byte[]{'\n', '\n'};


		private final ServerHttpResponse outputMessage;

		private final DeferredResult<?> deferredResult;

		private final List<HttpMessageConverter<?>> messageConverters;

		private final HttpHeaders httpHeaders;

		private final StringBuilder builder = new StringBuilder();

		private boolean sendFailed;


		public DefaultSseBuilder(HttpServletResponse response, Context context, DeferredResult<?> deferredResult,
				HttpHeaders httpHeaders) {
			this.outputMessage = new ServletServerHttpResponse(response);
			this.deferredResult = deferredResult;
			this.messageConverters = context.messageConverters();
			this.httpHeaders = httpHeaders;
		}

		@Override
		public void send(Object object) throws IOException {
			data(object);
		}

		@Override
		public SseBuilder id(String id) {
			Assert.hasLength(id, "Id must not be empty");
			assertNoLineSeparator(id);
			this.builder.append("id:").append(id).append('\n');
			return this;
		}

		@Override
		public SseBuilder event(String eventName) {
			Assert.hasLength(eventName, "Name must not be empty");
			assertNoLineSeparator(eventName);
			this.builder.append("event:").append(eventName).append('\n');
			return this;
		}

		@Override
		public SseBuilder retry(Duration duration) {
			Assert.notNull(duration, "Duration must not be null");
			this.builder.append("retry:").append(duration.toMillis()).append('\n');
			return this;
		}

		@Override
		public SseBuilder comment(String comment) {
			Assert.hasLength(comment, "Comment must not be empty");
			return field("", comment);
		}

		private SseBuilder field(String name, String value) {
			this.builder.append(name).append(':');
			appendFieldValue(name, value, this.builder);
			this.builder.append('\n');
			return this;
		}

		/**
		 * Append {@code value} to {@code output}, replacing each line separator
		 * ({@code "\n"}, {@code "\r"}, or {@code "\r\n"}) it contains with a new
		 * {@code field} line (that is, {@code "\n" + field + ":"}). This keeps a
		 * multi-line field value from breaking out of the current SSE field when
		 * written on the wire.
		 * @param field the name of the SSE field that {@code value} belongs to
		 * (for example, {@code "data"}), or an empty string for a comment
		 * @param value the field value to escape and append
		 * @param output the {@code StringBuilder} to append the escaped value to
		 */
		private static void appendFieldValue(String field, String value, StringBuilder output) {
			if (value.indexOf('\n') == -1 && value.indexOf('\r') == -1) {
				output.append(value);
				return;
			}
			String lineSeparatorReplacement = "\n" + field + ":";
			int length = value.length();
			for (int i = 0; i < length; i++) {
				char c = value.charAt(i);
				if (c == '\r') {
					if (i + 1 < length && value.charAt(i + 1) == '\n') {
						i++;
					}
					output.append(lineSeparatorReplacement);
				}
				else if (c == '\n') {
					output.append(lineSeparatorReplacement);
				}
				else {
					output.append(c);
				}
			}
		}

		/**
		 * Assert that the given single-line SSE field value, such as an
		 * {@code id} or {@code event} name, does not contain a line separator.
		 * @param content the field value to check
		 * @throws IllegalArgumentException if {@code content} contains {@code "\n"} or {@code "\r"}
		 */
		private static void assertNoLineSeparator(String content) {
			Assert.isTrue(content.indexOf('\n') == -1 && content.indexOf('\r') == -1,
					"illegal character '\\n' or '\\r' in event content");
		}

		@Override
		public void data(Object object) throws IOException {
			Assert.notNull(object, "Object must not be null");

			if (object instanceof String text) {
				writeString(text);
			}
			else {
				writeObject(object);
			}
		}

		private void writeString(String string) throws IOException {
			field("data", string);
			this.builder.append('\n');

			try {
				OutputStream body = this.outputMessage.getBody();
				body.write(builderBytes());
				body.flush();
			}
			catch (IOException ex) {
				this.sendFailed = true;
				throw ex;
			}
			finally {
				this.builder.setLength(0);
			}
		}

		@SuppressWarnings("unchecked")
		private void writeObject(Object data) throws IOException {
			this.builder.append("data:");
			try {
				this.outputMessage.getBody().write(builderBytes());

				Class<?> dataClass = data.getClass();
				for (HttpMessageConverter<?> converter : this.messageConverters) {
					if (converter.canWrite(dataClass, MediaType.APPLICATION_JSON)) {
						HttpMessageConverter<Object> objectConverter = (HttpMessageConverter<Object>) converter;
						ServerHttpResponse response = new MutableHeadersServerHttpResponse(this.outputMessage, this.httpHeaders);
						objectConverter.write(data, MediaType.APPLICATION_JSON, response);
						this.outputMessage.getBody().write(NL_NL);
						this.outputMessage.flush();
						return;
					}
				}
			}
			catch (IOException ex) {
				this.sendFailed = true;
				throw ex;
			}
			finally {
				this.builder.setLength(0);
			}
		}

		private byte[] builderBytes() {
			return this.builder.toString().getBytes(StandardCharsets.UTF_8);
		}

		@Override
		public void error(Throwable t) {
			if (this.sendFailed) {
				return;
			}
			this.deferredResult.setErrorResult(t);
		}

		@Override
		public void complete() {
			if (this.sendFailed) {
				return;
			}
			try {
				this.outputMessage.flush();
				this.deferredResult.setResult(null);
			}
			catch (IOException ex) {
				this.deferredResult.setErrorResult(ex);
			}
		}

		@Override
		public SseBuilder onTimeout(Runnable onTimeout) {
			this.deferredResult.onTimeout(onTimeout);
			return this;
		}

		@Override
		public SseBuilder onError(Consumer<Throwable> onError) {
			this.deferredResult.onError(onError);
			return this;
		}

		@Override
		public SseBuilder onComplete(Runnable onCompletion) {
			this.deferredResult.onCompletion(onCompletion);
			return this;
		}


		/**
		 * Wrap to silently ignore header changes HttpMessageConverter's that would
		 * otherwise cause HttpHeaders to raise exceptions.
		 */
		private static final class MutableHeadersServerHttpResponse extends DelegatingServerHttpResponse {

			private final HttpHeaders mutableHeaders = new HttpHeaders();

			public MutableHeadersServerHttpResponse(ServerHttpResponse delegate, HttpHeaders headers) {
				super(delegate);
				this.mutableHeaders.putAll(delegate.getHeaders());
				this.mutableHeaders.putAll(headers);
			}

			@Override
			public HttpHeaders getHeaders() {
				return this.mutableHeaders;
			}

		}

	}
}
