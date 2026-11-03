/*
 * Copyright 2002-2019 the original author or authors.
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

package org.springframework.web.reactive.resource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.resource.GzipSupport.GzippedFiles;
import org.springframework.web.testfixture.http.server.reactive.MockServerHttpRequest;
import org.springframework.web.testfixture.server.MockServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link EncodedResourceResolver}.
 *
 * @author Rossen Stoyanchev
 */
@ExtendWith(GzipSupport.class)
public class EncodedResourceResolverTests {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);


	private ResourceResolverChain resolver;

	private List<Resource> locations;


	@BeforeEach
	public void setup() {
		Cache cache = new ConcurrentMapCache("resourceCache");

		VersionResourceResolver versionResolver = new VersionResourceResolver();
		versionResolver.setStrategyMap(Collections.singletonMap("/**", new ContentVersionStrategy()));

		List<ResourceResolver> resolvers = new ArrayList<>();
		resolvers.add(new CachingResourceResolver(cache));
		resolvers.add(new EncodedResourceResolver());
		resolvers.add(versionResolver);
		resolvers.add(new PathResourceResolver());
		this.resolver = new DefaultResourceResolverChain(resolvers);

		this.locations = new ArrayList<>();
		this.locations.add(new ClassPathResource("test/", getClass()));
		this.locations.add(new ClassPathResource("testalternatepath/", getClass()));
	}


	@Test
	public void resolveGzipped(GzippedFiles gzippedFiles) {

		MockServerWebExchange exchange = MockServerWebExchange.from(
				MockServerHttpRequest.get("").header("Accept-Encoding", "gzip"));

		String file = "js/foo.js";
		gzippedFiles.create(file);
		Resource actual = this.resolver.resolveResource(exchange, file, this.locations).block(TIMEOUT);

		assertThat(actual.getDescription()).isEqualTo(getResource(file + ".gz").getDescription());
		assertThat(actual.getFilename()).isEqualTo(getResource(file).getFilename());

		boolean condition = actual instanceof HttpResource;
		assertThat(condition).isTrue();
		HttpHeaders headers = ((HttpResource) actual).getResponseHeaders();
		assertThat(headers.getFirst(HttpHeaders.CONTENT_ENCODING)).isEqualTo("gzip");
		assertThat(headers.getFirst(HttpHeaders.VARY)).isEqualTo("Accept-Encoding");
	}

	@Test
	public void resolveGzippedWithVersion(GzippedFiles gzippedFiles) {

		MockServerWebExchange exchange = MockServerWebExchange.from(
				MockServerHttpRequest.get("").header("Accept-Encoding", "gzip"));
		gzippedFiles.create("foo.css");
		String file = "foo-e36d2e05253c6c7085a91522ce43a0b4.css";
		Resource actual = this.resolver.resolveResource(exchange, file, this.locations).block(TIMEOUT);

		assertThat(actual.getDescription()).isEqualTo(getResource("foo.css.gz").getDescription());
		assertThat(actual.getFilename()).isEqualTo(getResource("foo.css").getFilename());
		boolean condition = actual instanceof HttpResource;
		assertThat(condition).isTrue();
	}

	@Test
	public void resolveFromCacheWithEncodingVariants(GzippedFiles gzippedFiles) {

		// 1. Resolve, and cache .gz variant

		MockServerWebExchange exchange = MockServerWebExchange.from(
				MockServerHttpRequest.get("").header("Accept-Encoding", "gzip"));

		String file = "js/foo.js";
		gzippedFiles.create(file);
		Resource resolved = this.resolver.resolveResource(exchange, file, this.locations).block(TIMEOUT);

		assertThat(resolved.getDescription()).isEqualTo(getResource(file + ".gz").getDescription());
		assertThat(resolved.getFilename()).isEqualTo(getResource(file).getFilename());
		boolean condition = resolved instanceof HttpResource;
		assertThat(condition).isTrue();

		// 2. Resolve unencoded resource

		exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/js/foo.js"));
		resolved = this.resolver.resolveResource(exchange, file, this.locations).block(TIMEOUT);

		assertThat(resolved.getDescription()).isEqualTo(getResource(file).getDescription());
		assertThat(resolved.getFilename()).isEqualTo(getResource(file).getFilename());
		boolean condition1 = resolved instanceof HttpResource;
		assertThat(condition1).isFalse();
	}

	@Test  // SPR-13149
	public void resolveWithNullRequest() {

		String file = "js/foo.js";
		Resource resolved = this.resolver.resolveResource(null, file, this.locations).block(TIMEOUT);

		assertThat(resolved.getDescription()).isEqualTo(getResource(file).getDescription());
		assertThat(resolved.getFilename()).isEqualTo(getResource(file).getFilename());
	}

	@ParameterizedTest
	@ValueSource(strings = {"gzip", "GZIP", " gzip ", "gzip;q=0.8", "deflate, gzip;q=0.5 , identity"})
	void resolveGzippedWithAcceptedCoding(String acceptEncoding, GzippedFiles gzippedFiles) {

		MockServerWebExchange exchange = MockServerWebExchange.from(
				MockServerHttpRequest.get("").header("Accept-Encoding", acceptEncoding));

		String file = "js/foo.js";
		gzippedFiles.create(file);
		Resource actual = this.resolver.resolveResource(exchange, file, this.locations).block(TIMEOUT);

		assertThat(actual.getDescription()).isEqualTo(getResource(file + ".gz").getDescription());
		assertThat(actual).isInstanceOf(HttpResource.class);
		HttpHeaders headers = ((HttpResource) actual).getResponseHeaders();
		assertThat(headers.getFirst(HttpHeaders.CONTENT_ENCODING)).isEqualTo("gzip");
	}

	@ParameterizedTest
	@ValueSource(strings = {"notgzip", "gzipx", "x-gzip", "gzip-9", "deflate, xgzip;q=1"})
	void resolveWithCodingContainingGzip(String acceptEncoding, GzippedFiles gzippedFiles) {

		MockServerWebExchange exchange = MockServerWebExchange.from(
				MockServerHttpRequest.get("").header("Accept-Encoding", acceptEncoding));

		String file = "js/foo.js";
		gzippedFiles.create(file);
		Resource actual = this.resolver.resolveResource(exchange, file, this.locations).block(TIMEOUT);

		assertThat(actual.getDescription()).isEqualTo(getResource(file).getDescription());
		assertThat(actual).isNotInstanceOf(HttpResource.class);
	}

	@Test
	void resolveBrotli() throws IOException {

		MockServerWebExchange exchange = MockServerWebExchange.from(
				MockServerHttpRequest.get("").header("Accept-Encoding", "br"));

		String file = "js/foo.js";
		File brFile = createBrotliFile(file);
		try {
			Resource actual = this.resolver.resolveResource(exchange, file, this.locations).block(TIMEOUT);

			assertThat(actual.getDescription()).isEqualTo(getResource(file + ".br").getDescription());
			assertThat(actual).isInstanceOf(HttpResource.class);
			HttpHeaders headers = ((HttpResource) actual).getResponseHeaders();
			assertThat(headers.getFirst(HttpHeaders.CONTENT_ENCODING)).isEqualTo("br");
		}
		finally {
			brFile.delete();
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"abr", "brotli", "br-fake", "deflate, x-br;q=1"})
	void resolveWithCodingContainingBr(String acceptEncoding) throws IOException {

		MockServerWebExchange exchange = MockServerWebExchange.from(
				MockServerHttpRequest.get("").header("Accept-Encoding", acceptEncoding));

		String file = "js/foo.js";
		File brFile = createBrotliFile(file);
		try {
			Resource actual = this.resolver.resolveResource(exchange, file, this.locations).block(TIMEOUT);

			assertThat(actual.getDescription()).isEqualTo(getResource(file).getDescription());
			assertThat(actual).isNotInstanceOf(HttpResource.class);
		}
		finally {
			brFile.delete();
		}
	}

	@Test
	void resolveFromCacheWithCodingContainingGzip(GzippedFiles gzippedFiles) {

		// 1. Resolve with a coding that merely contains "gzip"

		MockServerWebExchange exchange = MockServerWebExchange.from(
				MockServerHttpRequest.get("/js/foo.js").header("Accept-Encoding", "x-gzip"));

		String file = "js/foo.js";
		gzippedFiles.create(file);
		Resource resolved = this.resolver.resolveResource(exchange, file, this.locations).block(TIMEOUT);

		assertThat(resolved.getDescription()).isEqualTo(getResource(file).getDescription());
		assertThat(resolved).isNotInstanceOf(HttpResource.class);

		// 2. Resolve without Accept-Encoding: the cache must not serve a gzipped variant

		exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/js/foo.js"));
		resolved = this.resolver.resolveResource(exchange, file, this.locations).block(TIMEOUT);

		assertThat(resolved.getDescription()).isEqualTo(getResource(file).getDescription());
		assertThat(resolved).isNotInstanceOf(HttpResource.class);
	}

	@Test
	void parseAcceptEncoding() {
		MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(""));
		assertThat(EncodedResourceResolver.parseAcceptEncoding(exchange)).isEmpty();

		exchange = MockServerWebExchange.from(MockServerHttpRequest.get("").header("Accept-Encoding", "  "));
		assertThat(EncodedResourceResolver.parseAcceptEncoding(exchange)).isEmpty();

		exchange = MockServerWebExchange.from(MockServerHttpRequest.get("")
				.header("Accept-Encoding", "GZIP;q=0.8, Br , deflate,, x-gzip ; q=0.1"));
		assertThat(EncodedResourceResolver.parseAcceptEncoding(exchange))
				.containsExactly("gzip", "br", "deflate", "x-gzip");
	}

	private Resource getResource(String filePath) {
		return new ClassPathResource("test/" + filePath, getClass());
	}

	private File createBrotliFile(String filePath) throws IOException {
		File original = getResource(filePath).getFile();
		File brFile = new File(original.getAbsolutePath() + ".br");
		Files.copy(original.toPath(), brFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
		return brFile;
	}

}
