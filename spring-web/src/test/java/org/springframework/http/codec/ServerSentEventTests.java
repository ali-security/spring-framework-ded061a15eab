/*
 * Copyright 2002-2026 the original author or authors.
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

package org.springframework.http.codec;

import java.util.Collections;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;

import org.springframework.core.ResolvableType;
import org.springframework.http.MediaType;
import org.springframework.web.testfixture.http.server.reactive.MockServerHttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Tests for {@link ServerSentEvent}.
 * @author Brian Clozel
 */
class ServerSentEventTests {

	@ParameterizedTest(name = "{1}")
	@MethodSource("newLineCharacters")
	void rejectsInvalidId(String newLine, String description) {
		assertThatIllegalArgumentException().isThrownBy(() ->
				ServerSentEvent.<String>builder().id("first" + newLine + "second").build());
	}

	@ParameterizedTest(name = "{1}")
	@MethodSource("newLineCharacters")
	void rejectsInvalidEvent(String newLine, String description) {
		assertThatIllegalArgumentException().isThrownBy(() ->
				ServerSentEvent.<String>builder().event("first" + newLine + "second").build());
	}

	@Test // gh-36634
	void allowsNullId() {
		ServerSentEvent<String> event = ServerSentEvent.<String>builder().id(null).event("first").build();
		assertThat(event.id()).isNull();
		assertThat(format(event)).contains("event:first").doesNotContain("id");
	}

	@Test // gh-36634
	void allowsNullEvent() {
		ServerSentEvent<String> event = ServerSentEvent.<String>builder().id("42").event(null).build();
		assertThat(event.event()).isNull();
		assertThat(format(event)).contains("id:42").doesNotContain("event");
	}

	@ParameterizedTest(name = "{1}")
	@MethodSource("newLineCharacters")
	void supportMultiLineComments(String newLine, String description) {
		ServerSentEvent<String> event = ServerSentEvent.<String>builder()
				.comment("first" + newLine + "data:second").data("third").build();
		assertThat(format(event)).isEqualTo(":first\n:data:second\ndata:third\n\n");
	}

	private static String format(ServerSentEvent<String> event) {
		MockServerHttpResponse response = new MockServerHttpResponse();
		new ServerSentEventHttpMessageWriter().write(Mono.just(event), ResolvableType.forClass(ServerSentEvent.class),
				MediaType.TEXT_EVENT_STREAM, response, Collections.emptyMap()).block();
		return response.getBodyAsString().block();
	}

	private static Stream<Arguments> newLineCharacters() {
		return Stream.of(
				Arguments.of("\n", "LF"),
				Arguments.of("\r", "CR"),
				Arguments.of("\r\n", "CRLF")
		);
	}

}
