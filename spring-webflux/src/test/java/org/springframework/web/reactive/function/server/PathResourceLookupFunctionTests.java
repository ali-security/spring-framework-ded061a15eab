/*
 * Copyright 2002-2020 the original author or authors.
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

package org.springframework.web.reactive.function.server;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.web.testfixture.http.server.reactive.MockServerHttpRequest;
import org.springframework.web.testfixture.server.MockServerWebExchange;

/**
 * @author Arjen Poutsma
 */
public class PathResourceLookupFunctionTests {

	@TempDir
	Path tempDir;

	@Test
	public void normal() throws Exception {
		ClassPathResource location = new ClassPathResource("org/springframework/web/reactive/function/server/");

		PathResourceLookupFunction
				function = new PathResourceLookupFunction("/resources/**", location);
		MockServerHttpRequest mockRequest = MockServerHttpRequest.get("https://localhost/resources/response.txt").build();
		ServerRequest request = new DefaultServerRequest(MockServerWebExchange.from(mockRequest), Collections.emptyList());
		Mono<Resource> result = function.apply(request);

		File expected = new ClassPathResource("response.txt", getClass()).getFile();
		StepVerifier.create(result)
				.expectNextMatches(resource -> {
					try {
						return expected.equals(resource.getFile());
					}
					catch (IOException ex) {
						return false;
					}
				})
				.expectComplete()
				.verify();
	}

	@Test
	public void subPath() throws Exception {
		ClassPathResource location = new ClassPathResource("org/springframework/web/reactive/function/server/");

		PathResourceLookupFunction function = new PathResourceLookupFunction("/resources/**", location);
		MockServerHttpRequest mockRequest = MockServerHttpRequest.get("https://localhost/resources/child/response.txt").build();
		ServerRequest request = new DefaultServerRequest(MockServerWebExchange.from(mockRequest), Collections.emptyList());
		Mono<Resource> result = function.apply(request);
		String path = "org/springframework/web/reactive/function/server/child/response.txt";
		File expected = new ClassPathResource(path).getFile();
		StepVerifier.create(result)
				.expectNextMatches(resource -> {
					try {
						return expected.equals(resource.getFile());
					}
					catch (IOException ex) {
						return false;
					}
				})
				.expectComplete()
				.verify();
	}

	@Test
	public void notFound() throws Exception {
		ClassPathResource location = new ClassPathResource("org/springframework/web/reactive/function/server/");

		PathResourceLookupFunction function = new PathResourceLookupFunction("/resources/**", location);
		MockServerHttpRequest mockRequest = MockServerHttpRequest.get("https://example.com").build();
		ServerRequest request = new DefaultServerRequest(MockServerWebExchange.from(mockRequest), Collections.emptyList());
		Mono<Resource> result = function.apply(request);
		StepVerifier.create(result)
				.expectComplete()
				.verify();
	}

	@Test
	public void composeResourceLookupFunction() throws Exception {
		ClassPathResource defaultResource = new ClassPathResource("response.txt", getClass());

		Function<ServerRequest, Mono<Resource>> lookupFunction =
				new PathResourceLookupFunction("/resources/**",
						new ClassPathResource("org/springframework/web/reactive/function/server/"));

		Function<ServerRequest, Mono<Resource>> customLookupFunction =
				lookupFunction.andThen(resourceMono -> resourceMono
								.switchIfEmpty(Mono.just(defaultResource)));

		MockServerHttpRequest mockRequest = MockServerHttpRequest.get("https://localhost/resources/foo").build();
		ServerRequest request = new DefaultServerRequest(MockServerWebExchange.from(mockRequest), Collections.emptyList());

		Mono<Resource> result = customLookupFunction.apply(request);
		StepVerifier.create(result)
				.expectNextMatches(resource -> {
					try {
						return defaultResource.getFile().equals(resource.getFile());
					}
					catch (IOException ex) {
						return false;
					}
				})
				.expectComplete()
				.verify();
	}

	@Test
	void fileSystemResourceLocation() throws Exception {
		PathResourceLookupFunction function = new PathResourceLookupFunction("/resources/**", initFileSystemLocation());
		Mono<Resource> result = function.apply(initRequest("/resources/sub/file.txt"));

		File expected = this.tempDir.resolve("static/sub/file.txt").toFile();
		StepVerifier.create(result)
				.expectNextMatches(resource -> {
					try {
						return expected.equals(resource.getFile());
					}
					catch (IOException ex) {
						return false;
					}
				})
				.expectComplete()
				.verify();
	}

	@Test
	void fileSystemResourceLocationWithEncodedFilename() throws Exception {
		PathResourceLookupFunction function = new PathResourceLookupFunction("/resources/**", initFileSystemLocation());
		Mono<Resource> result = function.apply(initRequest("/resources/a%20b.txt"));

		File expected = this.tempDir.resolve("static/a b.txt").toFile();
		StepVerifier.create(result)
				.expectNextMatches(resource -> {
					try {
						return expected.equals(resource.getFile());
					}
					catch (IOException ex) {
						return false;
					}
				})
				.expectComplete()
				.verify();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"sub%2f%2f..%2f..%2fsecret.txt",
			"sub%2F%2F..%2F..%2Fsecret.txt",
			"sub/%2f..%2f..%2fsecret.txt",
			"sub/%2f%2e%2e%2f%2e%2e%2fsecret.txt",
			"sub%2f%2f%2f..%2f..%2fsecret.txt",
			"..%2fsecret.txt",
			"%2e%2e/secret.txt",
			"%2e%2e%2fsecret.txt",
			"%2E%2E%2Fsecret.txt",
			"%2e%2e%5csecret.txt",
			"%5C%5C..%5C..%5Csecret.txt",
			"sub/..%2f..%2fsecret.txt",
			"sub/%2e%2e/%2e%2e/secret.txt",
			"%252e%252e%252fsecret.txt",
			"..%255csecret.txt"
	})
	void fileSystemResourceLocationWithTraversal(String path) throws Exception {
		PathResourceLookupFunction function = new PathResourceLookupFunction("/resources/**", initFileSystemLocation());
		Mono<Resource> result = function.apply(initRequest("/resources/" + path));

		StepVerifier.create(result)
				.expectComplete()
				.verify();
	}

	private Resource initFileSystemLocation() throws Exception {
		Path location = Files.createDirectories(this.tempDir.resolve("static"));
		Files.createDirectories(location.resolve("sub"));
		Files.writeString(location.resolve("sub/file.txt"), "file");
		Files.writeString(location.resolve("a b.txt"), "file");
		Files.writeString(this.tempDir.resolve("secret.txt"), "secret");
		return new FileSystemResource(location.toString() + "/");
	}

	private ServerRequest initRequest(String path) {
		// Use an already encoded URI, as the URI template variant would encode "%" again
		MockServerHttpRequest mockRequest =
				MockServerHttpRequest.method(HttpMethod.GET, URI.create("https://localhost" + path)).build();
		return new DefaultServerRequest(MockServerWebExchange.from(mockRequest), Collections.emptyList());
	}

}
