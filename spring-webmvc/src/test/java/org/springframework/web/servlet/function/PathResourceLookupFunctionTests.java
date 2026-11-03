/*
 * Copyright 2002-2024 the original author or authors.
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

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.web.context.support.ServletContextResource;
import org.springframework.web.servlet.handler.PathPatternsTestUtils;
import org.springframework.web.testfixture.servlet.MockServletContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Arjen Poutsma
 */
class PathResourceLookupFunctionTests {

	@TempDir
	Path tempDir;

	@Test
	void normal() throws Exception {
		ClassPathResource location = new ClassPathResource("org/springframework/web/servlet/function/");
		PathResourceLookupFunction function = new PathResourceLookupFunction("/resources/**", location);
		ServerRequest request = initRequest("GET", "/resources/response.txt");

		Optional<Resource> result = function.apply(request);
		assertThat(result).isPresent();

		File expected = new ClassPathResource("response.txt", getClass()).getFile();
		assertThat(result.get().getFile()).isEqualTo(expected);
	}

	@Test
	void subPath() throws Exception {
		ClassPathResource location = new ClassPathResource("org/springframework/web/servlet/function/");
		PathResourceLookupFunction function = new PathResourceLookupFunction("/resources/**", location);
		ServerRequest request = initRequest("GET", "/resources/child/response.txt");

		Optional<Resource> result = function.apply(request);
		assertThat(result).isPresent();

		File expected = new ClassPathResource("org/springframework/web/servlet/function/child/response.txt").getFile();
		assertThat(result.get().getFile()).isEqualTo(expected);
	}

	@Test
	void notFound() {
		ClassPathResource location = new ClassPathResource("org/springframework/web/reactive/function/server/");
		PathResourceLookupFunction function = new PathResourceLookupFunction("/resources/**", location);
		ServerRequest request = initRequest("GET", "/resources/foo.txt");

		Optional<Resource> result = function.apply(request);
		assertThat(result).isNotPresent();
	}

	@Test
	void composeResourceLookupFunction() throws Exception {
		ClassPathResource defaultResource = new ClassPathResource("response.txt", getClass());

		Function<ServerRequest, Optional<Resource>> lookupFunction =
				new PathResourceLookupFunction("/resources/**",
						new ClassPathResource("org/springframework/web/servlet/function/"));

		Function<ServerRequest, Optional<Resource>> customLookupFunction =
				lookupFunction.andThen((Optional<Resource> optionalResource) -> {
					if (optionalResource.isPresent()) {
						return optionalResource;
					}
					else {
						return Optional.of(defaultResource);
					}
				});

		ServerRequest request = initRequest("GET", "/resources/foo");

		Optional<Resource> result = customLookupFunction.apply(request);
		assertThat(result).isPresent();

		assertThat(result.get().getFile()).isEqualTo(defaultResource.getFile());
	}

	@Test
	void fileSystemResourceLocation() throws Exception {
		PathResourceLookupFunction function = new PathResourceLookupFunction("/resources/**", initFileSystemLocation());

		Optional<Resource> result = function.apply(initRequest("GET", "/resources/sub/file.txt"));
		assertThat(result).isPresent();
		assertThat(result.get().getFile()).isEqualTo(this.tempDir.resolve("static/sub/file.txt").toFile());
	}

	@Test
	void fileSystemResourceLocationWithEncodedFilename() throws Exception {
		PathResourceLookupFunction function = new PathResourceLookupFunction("/resources/**", initFileSystemLocation());

		Optional<Resource> result = function.apply(initRequest("GET", "/resources/a%20b.txt"));
		assertThat(result).isPresent();
		assertThat(result.get().getFile()).isEqualTo(this.tempDir.resolve("static/a b.txt").toFile());
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

		Optional<Resource> result = function.apply(initRequest("GET", "/resources/" + path));
		assertThat(result).isNotPresent();
	}

	@Test
	@DisabledOnOs(value = OS.WINDOWS, disabledReason = "Backslash is not allowed in a file name")
	void fileSystemResourceLocationWithEncodedBackslashTraversal() throws Exception {
		// On Windows "..\" is a parent directory reference. Here the file name
		// literally contains it, so that only the under-location check can reject it.
		PathResourceLookupFunction function = new PathResourceLookupFunction("/resources/**", initFileSystemLocation());
		Files.writeString(this.tempDir.resolve("static/sub/..\\secret.txt"), "secret");

		Optional<Resource> result = function.apply(initRequest("GET", "/resources/sub/..%5Csecret.txt"));
		assertThat(result).isNotPresent();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"link/%2e%2e/secret.txt",
			"link/%2E%2E/secret.txt",
			"link%2f%2e%2e%2fsecret.txt",
			"link/..%2fsecret.txt",
			"link/%252e%252e/secret.txt"
	})
	@DisabledOnOs(value = OS.WINDOWS, disabledReason = "Creating symbolic links requires elevated privileges")
	void fileSystemResourceLocationWithEncodedTraversalThroughSymlink(String path) throws Exception {
		// An encoded "../" that StringUtils#cleanPath removes passes validation, but
		// the file system resolves ".." against the symlink target, outside the location.
		Resource location = initFileSystemLocation();
		Files.createDirectories(this.tempDir.resolve("outside"));
		Files.createSymbolicLink(this.tempDir.resolve("static/link"), this.tempDir.resolve("outside"));
		PathResourceLookupFunction function = new PathResourceLookupFunction("/resources/**", location);

		Optional<Resource> result = function.apply(initRequest("GET", "/resources/" + path));
		assertThat(result).isNotPresent();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"ĮĮįsecret%2Etxt",
			"sub/../%25C4%25AE%25C4%25AE%25C4%25AFsecret%25252Etxt",
			"sub/%2e%2e/%25C4%25AE%25C4%25AE%25C4%25AFsecret%25252Etxt"
	})
	void fileSystemResourceLocationWithTraversalRevealedByDecodingNonAsciiCharacters(String path) throws Exception {
		// U+012E and U+012F must not be truncated to '.' and '/' while decoding
		// the path for the location, after it has passed validation.
		PathResourceLookupFunction function = new PathResourceLookupFunction("/resources/**", initFileSystemLocation());

		Optional<Resource> result = function.apply(initRequest("GET", "/resources/" + path));
		assertThat(result).isNotPresent();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"%20śpřìńġ.txt",
			"%20%C5%9Bp%C5%99%C3%AC%C5%84%C4%A1.txt"
	})
	void fileSystemResourceLocationWithEncodedNonAsciiFilename(String path) throws Exception {
		Resource location = initFileSystemLocation();
		Files.writeString(this.tempDir.resolve("static/ śpřìńġ.txt"), "file");
		PathResourceLookupFunction function = new PathResourceLookupFunction("/resources/**", location);

		Optional<Resource> result = function.apply(initRequest("GET", "/resources/" + path));
		assertThat(result).isPresent();
		assertThat(result.get().getFile()).isEqualTo(this.tempDir.resolve("static/ śpřìńġ.txt").toFile());
	}

	@Test
	void servletContextResourceLocation() throws Exception {
		PathResourceLookupFunction function = new PathResourceLookupFunction("/resources/**", initServletContextLocation());

		Optional<Resource> result = function.apply(initRequest("GET", "/resources/sub/file.txt"));
		assertThat(result).isPresent();
		assertThat(result.get()).isInstanceOf(ServletContextResource.class);
		assertThat(((ServletContextResource) result.get()).getPath()).isEqualTo("/static/sub/file.txt");
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"sub%2f%2f..%2f..%2fsecret.txt",
			"sub/%2f..%2f..%2fsecret.txt",
			"..%2fsecret.txt",
			"%2e%2e%2fsecret.txt",
			"%2e%2e%5csecret.txt",
			"%252e%252e%252fsecret.txt",
			"sub/%252e%252e%252f%252e%252e%252fsecret.txt"
	})
	void servletContextResourceLocationWithTraversal(String path) throws Exception {
		PathResourceLookupFunction function = new PathResourceLookupFunction("/resources/**", initServletContextLocation());

		Optional<Resource> result = function.apply(initRequest("GET", "/resources/" + path));
		assertThat(result).isNotPresent();
	}

	@Test
	@DisabledOnOs(value = OS.WINDOWS, disabledReason = "Backslash is not allowed in a file name")
	void servletContextResourceLocationWithEncodedBackslashTraversal() throws Exception {
		// On Windows "..\" is a parent directory reference. Here the file name
		// literally contains it, so that only the under-location check can reject it.
		PathResourceLookupFunction function = new PathResourceLookupFunction("/resources/**", initServletContextLocation());
		Files.writeString(this.tempDir.resolve("static/sub/..\\secret.txt"), "secret");

		Optional<Resource> result = function.apply(initRequest("GET", "/resources/sub/..%255Csecret.txt"));
		assertThat(result).isNotPresent();
	}

	@ParameterizedTest  // gh-36693
	@ValueSource(strings = {
			"web-inf/secret.txt",
			"Meta-Inf/secret.txt",
			"%77eb-inf/secret.txt"
	})
	void fileSystemResourceLocationWithWebInfOrMetaInfIgnoringCase(String path) throws Exception {
		Resource location = initFileSystemLocation();
		Path staticDir = this.tempDir.resolve("static");
		Files.writeString(Files.createDirectories(staticDir.resolve("web-inf")).resolve("secret.txt"), "secret");
		Files.writeString(Files.createDirectories(staticDir.resolve("Meta-Inf")).resolve("secret.txt"), "secret");
		PathResourceLookupFunction function = new PathResourceLookupFunction("/resources/**", location);

		Optional<Resource> result = function.apply(initRequest("GET", "/resources/" + path));
		assertThat(result).isNotPresent();
	}

	private Resource initFileSystemLocation() throws Exception {
		Path location = initFiles();
		return new FileSystemResource(location.toString() + "/");
	}

	private Resource initServletContextLocation() throws Exception {
		initFiles();
		String basePath = this.tempDir.toUri().toString();
		if (basePath.endsWith("/")) {
			basePath = basePath.substring(0, basePath.length() - 1);
		}
		MockServletContext servletContext = new MockServletContext(basePath, new DefaultResourceLoader());
		return new ServletContextResource(servletContext, "/static/");
	}

	private Path initFiles() throws Exception {
		Path location = Files.createDirectories(this.tempDir.resolve("static"));
		Files.createDirectories(location.resolve("sub"));
		Files.writeString(location.resolve("sub/file.txt"), "file");
		Files.writeString(location.resolve("a b.txt"), "file");
		Files.writeString(this.tempDir.resolve("secret.txt"), "secret");
		return location;
	}

	private ServerRequest initRequest(String httpMethod, String requestUri) {
		return new DefaultServerRequest(
				PathPatternsTestUtils.initRequest(httpMethod, requestUri, true),
				Collections.emptyList());
	}

}
