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

package org.springframework.web.servlet.resource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.lang.Nullable;
import org.springframework.web.testfixture.servlet.MockHttpServletRequest;
import org.springframework.web.util.ServletRequestPathUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link VersionResourceResolver}
 *
 * @author Brian Clozel
 * @author Sam Brannen
 */
class VersionResourceResolverTests {

	private List<Resource> locations = List.of(
			new ClassPathResource("test/", getClass()),
			new ClassPathResource("testalternatepath/", getClass()));

	private VersionResourceResolver resolver = new VersionResourceResolver();

	private ResourceResolverChain chain = mock();

	private VersionStrategy versionStrategy = mock();


	@Test
	void resolveResourceExisting() throws Exception {
		String file = "bar.css";
		Resource expected = new ClassPathResource("test/" + file, getClass());
		given(this.chain.resolveResource(null, file, this.locations)).willReturn(expected);

		this.resolver.setStrategyMap(Collections.singletonMap("/**", this.versionStrategy));
		Resource actual = this.resolver.resolveResourceInternal(null, file, this.locations, this.chain);
		assertThat(actual).isEqualTo(expected);
		verify(this.chain, times(1)).resolveResource(null, file, this.locations);
		verify(this.versionStrategy, never()).extractVersion(file);
	}

	@Test
	void resolveResourceNoVersionStrategy() throws Exception {
		String file = "missing.css";
		given(this.chain.resolveResource(null, file, this.locations)).willReturn(null);

		this.resolver.setStrategyMap(Collections.emptyMap());
		Resource actual = this.resolver.resolveResourceInternal(null, file, this.locations, this.chain);
		assertThat((Object) actual).isNull();
		verify(this.chain, times(1)).resolveResource(null, file, this.locations);
	}

	@Test
	void resolveResourceNoVersionInPath() throws Exception {
		String file = "bar.css";
		given(this.chain.resolveResource(null, file, this.locations)).willReturn(null);
		given(this.versionStrategy.extractVersion(file)).willReturn("");

		this.resolver.setStrategyMap(Collections.singletonMap("/**", this.versionStrategy));
		Resource actual = this.resolver.resolveResourceInternal(null, file, this.locations, this.chain);
		assertThat((Object) actual).isNull();
		verify(this.chain, times(1)).resolveResource(null, file, this.locations);
		verify(this.versionStrategy, times(1)).extractVersion(file);
	}

	@Test
	void resolveResourceNoResourceAfterVersionRemoved() throws Exception {
		String versionFile = "bar-version.css";
		String version = "version";
		String file = "bar.css";
		given(this.chain.resolveResource(null, versionFile, this.locations)).willReturn(null);
		given(this.chain.resolveResource(null, file, this.locations)).willReturn(null);
		given(this.versionStrategy.extractVersion(versionFile)).willReturn(version);
		given(this.versionStrategy.removeVersion(versionFile, version)).willReturn(file);

		this.resolver.setStrategyMap(Collections.singletonMap("/**", this.versionStrategy));
		Resource actual = this.resolver.resolveResourceInternal(null, versionFile, this.locations, this.chain);
		assertThat((Object) actual).isNull();
		verify(this.versionStrategy, times(1)).removeVersion(versionFile, version);
	}

	@Test
	void resolveResourceVersionDoesNotMatch() throws Exception {
		String versionFile = "bar-version.css";
		String version = "version";
		String file = "bar.css";
		Resource expected = new ClassPathResource("test/" + file, getClass());
		given(this.chain.resolveResource(null, versionFile, this.locations)).willReturn(null);
		given(this.chain.resolveResource(null, file, this.locations)).willReturn(expected);
		given(this.versionStrategy.extractVersion(versionFile)).willReturn(version);
		given(this.versionStrategy.removeVersion(versionFile, version)).willReturn(file);
		given(this.versionStrategy.getResourceVersion(expected)).willReturn("newer-version");

		this.resolver.setStrategyMap(Collections.singletonMap("/**", this.versionStrategy));
		Resource actual = this.resolver.resolveResourceInternal(null, versionFile, this.locations, this.chain);
		assertThat((Object) actual).isNull();
		verify(this.versionStrategy, times(1)).getResourceVersion(expected);
	}

	@Test
	void resolveResourceSuccess() throws Exception {
		String versionFile = "bar-version.css";
		String version = "version";
		String file = "bar.css";
		Resource expected = new ClassPathResource("test/" + file, getClass());
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/resources/bar-version.css");
		given(this.chain.resolveResource(request, versionFile, this.locations)).willReturn(null);
		given(this.chain.resolveResource(request, file, this.locations)).willReturn(expected);
		given(this.versionStrategy.extractVersion(versionFile)).willReturn(version);
		given(this.versionStrategy.removeVersion(versionFile, version)).willReturn(file);
		given(this.versionStrategy.getResourceVersion(expected)).willReturn(version);

		this.resolver
				.setStrategyMap(Collections.singletonMap("/**", this.versionStrategy));
		Resource actual = this.resolver.resolveResourceInternal(request, versionFile, this.locations, this.chain);
		assertThat(actual.getFilename()).isEqualTo(expected.getFilename());
		verify(this.versionStrategy, times(1)).getResourceVersion(expected);
		assertThat(actual).isInstanceOf(HttpResource.class);
		assertThat(((HttpResource)actual).getResponseHeaders().getETag()).isEqualTo("W/\"" + version + "\"");
	}

	@ParameterizedTest  // gh-36698
	@ValueSource(strings = {
			"",
			"../secret.txt",
			"css/../../secret.txt",
			"..\\secret.txt",
			"%2e%2e/secret.txt",
			"%2E%2E%2Fsecret.txt",
			"%2e%2e%5csecret.txt",
			"%252e%252e%252fsecret.txt",
			"WEB-INF/web.xml",
			"web-inf/web.xml",
			"META-INF/MANIFEST.MF",
			"Meta-Inf/MANIFEST.MF",
			"%77eb-inf/web.xml",
			"file:/etc/passwd",
			"/url:file:/etc/passwd"
	})
	void resolveResourceIgnoresInvalidPathAfterVersionRemoved(String simplePath) {
		String versionFile = "bar-version.css";
		String version = "version";
		given(this.chain.resolveResource(null, versionFile, this.locations)).willReturn(null);
		given(this.versionStrategy.extractVersion(versionFile)).willReturn(version);
		given(this.versionStrategy.removeVersion(versionFile, version)).willReturn(simplePath);

		this.resolver.setStrategyMap(Collections.singletonMap("/**", this.versionStrategy));
		Resource actual = this.resolver.resolveResourceInternal(null, versionFile, this.locations, this.chain);
		assertThat(actual).isNull();
		verify(this.chain, never()).resolveResource(null, simplePath, this.locations);
		verify(this.versionStrategy, never()).getResourceVersion(any());
	}

	@Test  // gh-36698
	void resolveResourceWithRepeatedVersionDoesNotEscapeLocation(@TempDir Path tempDir) throws Exception {
		// Removing every occurrence of the version turns ".-{version}./" into "../"
		Path location = Files.createDirectories(tempDir.resolve("static"));
		Files.writeString(location.resolve("public.txt"), "public");
		Path secret = Files.writeString(tempDir.resolve("secret.txt"), "secret");
		ContentVersionStrategy strategy = new ContentVersionStrategy();
		String publicVersion = strategy.getResourceVersion(new FileSystemResource(location.resolve("public.txt")));
		String version = strategy.getResourceVersion(new FileSystemResource(secret));

		Resource resource = resolveFromFileSystem(location, strategy, null, "public-" + publicVersion + ".txt");
		assertThat(resource).isNotNull();
		assertThat(resource.getContentAsByteArray()).isEqualTo("public".getBytes());

		resource = resolveFromFileSystem(location, strategy, null, ".-" + version + "./secret-" + version + ".txt");
		assertThat(resource).isNull();
	}

	@Test  // gh-36698
	void resolveResourceWithTraversalAfterVersionRemovedDoesNotEscapeLocation(@TempDir Path tempDir) throws Exception {
		// Removing only the last occurrence of the version can still turn ".-{version}./" into "../"
		Path location = Files.createDirectories(tempDir.resolve("static"));
		Path secret = Files.writeString(tempDir.resolve("secret"), "secret");
		ContentVersionStrategy strategy = new ContentVersionStrategy();
		String version = strategy.getResourceVersion(new FileSystemResource(secret));

		Resource resource = resolveFromFileSystem(location, strategy, null, ".-" + version + "./secret");
		assertThat(resource).isNull();
	}

	@Test  // gh-36698
	void resolveResourceWithEncodedTraversalAfterVersionRemovedDoesNotEscapeLocation(@TempDir Path tempDir) throws Exception {
		// "%2e-{version}.%2fsecret%2etxt" becomes "%2e.%2fsecret%2etxt", i.e. "../secret.txt" once decoded
		Path location = Files.createDirectories(tempDir.resolve("static"));
		Path secret = Files.writeString(tempDir.resolve("secret.txt"), "secret");
		ContentVersionStrategy strategy = new ContentVersionStrategy();
		String version = strategy.getResourceVersion(new FileSystemResource(secret));

		String path = "%2e-" + version + ".%2fsecret%2etxt";
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/resources/" + path);
		ServletRequestPathUtils.parseAndCache(request);

		Resource resource = resolveFromFileSystem(location, strategy, request, path);
		assertThat(resource).isNull();
	}

	@Test  // gh-36698
	void resolveResourceDoesNotComputeVersionOfResourceOutsideLocation(@TempDir Path tempDir) throws Exception {
		// Computing the content version of an arbitrary file outside the location
		// (e.g. a very large file or a device) can hold the request indefinitely
		Path location = Files.createDirectories(tempDir.resolve("static"));
		Files.write(tempDir.resolve("large.bin"), new byte[8192]);
		Files.write(tempDir.resolve("large"), new byte[8192]);
		List<Resource> versionedResources = new ArrayList<>();
		ContentVersionStrategy strategy = new ContentVersionStrategy() {
			@Override
			public String getResourceVersion(Resource resource) {
				versionedResources.add(resource);
				return super.getResourceVersion(resource);
			}
		};

		for (String path : List.of(".-x./large-x.bin", ".-x./large", "%2e-x.%2flarge%2ebin")) {
			MockHttpServletRequest request = new MockHttpServletRequest("GET", "/resources/" + path);
			ServletRequestPathUtils.parseAndCache(request);
			assertThat(resolveFromFileSystem(location, strategy, request, path)).isNull();
		}
		assertThat(versionedResources).isEmpty();
	}

	@Nullable
	private Resource resolveFromFileSystem(Path location, VersionStrategy strategy,
			@Nullable HttpServletRequest request, String requestPath) {

		this.resolver.setStrategyMap(Collections.singletonMap("/**", strategy));
		List<Resource> fileSystemLocations = List.of(new FileSystemResource(location.toString() + "/"));
		ResourceResolverChain resolverChain =
				new DefaultResourceResolverChain(List.of(this.resolver, new PathResourceResolver()));
		return resolverChain.resolveResource(request, requestPath, fileSystemLocations);
	}

	@Test
	void getStrategyForPath() throws Exception {
		Map<String, VersionStrategy> strategies = new HashMap<>();
		VersionStrategy jsStrategy = mock();
		VersionStrategy catchAllStrategy = mock();
		strategies.put("/**", catchAllStrategy);
		strategies.put("/**/*.js", jsStrategy);
		this.resolver.setStrategyMap(strategies);

		assertThat(this.resolver.getStrategyForPath("foo.css")).isEqualTo(catchAllStrategy);
		assertThat(this.resolver.getStrategyForPath("foo-js.css")).isEqualTo(catchAllStrategy);
		assertThat(this.resolver.getStrategyForPath("foo.js")).isEqualTo(jsStrategy);
		assertThat(this.resolver.getStrategyForPath("bar/foo.js")).isEqualTo(jsStrategy);
	}

	// SPR-13883
	@Test
	void shouldConfigureFixedPrefixAutomatically() throws Exception {
		this.resolver.addFixedVersionStrategy("fixedversion", "/js/**", "/css/**", "/fixedversion/css/**");

		assertThat(this.resolver.getStrategyMap()).hasSize(4);
		assertThat(this.resolver.getStrategyForPath("js/something.js")).isInstanceOf(FixedVersionStrategy.class);
		assertThat(this.resolver.getStrategyForPath("fixedversion/js/something.js")).isInstanceOf(FixedVersionStrategy.class);
		assertThat(this.resolver.getStrategyForPath("css/something.css")).isInstanceOf(FixedVersionStrategy.class);
		assertThat(this.resolver.getStrategyForPath("fixedversion/css/something.css")).isInstanceOf(FixedVersionStrategy.class);
	}

	@Test // SPR-15372
	void resolveUrlPathNoVersionStrategy() throws Exception {
		given(this.chain.resolveUrlPath("/foo.css", this.locations)).willReturn("/foo.css");
		String resolved = this.resolver.resolveUrlPathInternal("/foo.css", this.locations, this.chain);
		assertThat(resolved).isEqualTo("/foo.css");
	}

}
