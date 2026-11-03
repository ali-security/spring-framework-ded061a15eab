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
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.server.PathContainer;
import org.springframework.util.Assert;
import org.springframework.util.ResourceUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.context.support.ServletContextResource;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Lookup function used by {@link RouterFunctions#resources(String, Resource)}.
 *
 * @author Arjen Poutsma
 * @author Rossen Stoyanchev
 * @since 5.2
 */
class PathResourceLookupFunction implements Function<ServerRequest, Optional<Resource>> {

	private final PathPattern pattern;

	private final Resource location;


	public PathResourceLookupFunction(String pattern, Resource location) {
		Assert.hasLength(pattern, "'pattern' must not be empty");
		Assert.notNull(location, "'location' must not be null");
		this.pattern = PathPatternParser.defaultInstance.parse(pattern);
		this.location = location;
	}


	@Override
	public Optional<Resource> apply(ServerRequest request) {
		PathContainer pathContainer = request.requestPath().pathWithinApplication();
		if (!this.pattern.matches(pathContainer)) {
			return Optional.empty();
		}

		pathContainer = this.pattern.extractPathWithinPattern(pathContainer);
		String path = processPath(pathContainer.value());
		if (!StringUtils.hasText(path) || isInvalidPath(path)) {
			return Optional.empty();
		}
		if (isInvalidEncodedInputPath(path)) {
			return Optional.empty();
		}

		if (!(this.location instanceof UrlResource)) {
			path = uriDecode(path, StandardCharsets.UTF_8);
		}

		try {
			Resource resource = this.location.createRelative(path);
			if (resource.isReadable() && isResourceUnderLocation(resource)) {
				return Optional.of(resource);
			}
			else {
				return Optional.empty();
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * Process the given resource path.
	 * <p>The default implementation replaces:
	 * <ul>
	 * <li>Backslash with forward slash.
	 * <li>Duplicate occurrences of slash with a single slash.
	 * <li>Any combination of leading slash and control characters (00-1F and 7F)
	 * with a single "/" or "". For example {@code "  / // foo/bar"}
	 * becomes {@code "/foo/bar"}.
	 * </ul>
	 */
	protected String processPath(String path) {
		path = StringUtils.replace(path, "\\", "/");
		path = cleanDuplicateSlashes(path);
		path = cleanLeadingSlash(path);
		return normalizePath(path);
	}

	private String cleanDuplicateSlashes(String path) {
		StringBuilder sb = null;
		char prev = 0;
		for (int i = 0; i < path.length(); i++) {
			char curr = path.charAt(i);
			try {
				if ((curr == '/') && (prev == '/')) {
					if (sb == null) {
						sb = new StringBuilder(path.substring(0, i));
					}
					continue;
				}
				if (sb != null) {
					sb.append(path.charAt(i));
				}
			}
			finally {
				prev = curr;
			}
		}
		return sb != null ? sb.toString() : path;
	}

	private String cleanLeadingSlash(String path) {
		boolean slash = false;
		for (int i = 0; i < path.length(); i++) {
			if (path.charAt(i) == '/') {
				slash = true;
			}
			else if (path.charAt(i) > ' ' && path.charAt(i) != 127) {
				if (i == 0 || (i == 1 && slash)) {
					return path;
				}
				return (slash ? "/" + path.substring(i) : path.substring(i));
			}
		}
		return (slash ? "/" : "");
	}

	private static String normalizePath(String path) {
		String result = path;
		result = decode(result);
		if (result.contains("%")) {
			result = decode(result);
		}
		if (!StringUtils.hasText(result)) {
			return result;
		}
		if (result.contains("../")) {
			return StringUtils.cleanPath(result);
		}
		return path;
	}

	private static String decode(String path) {
		try {
			return uriDecode(path, StandardCharsets.UTF_8);
		}
		catch (Exception ex) {
			return "";
		}
	}

	/**
	 * Decode the given encoded URI component value by replacing "<i>{@code %xy}</i>"
	 * sequences by an hexadecimal representation of the character in the specified
	 * charset, letting other characters unchanged.
	 * @param source the encoded {@code String}
	 * @param charset the character encoding to use to decode the "<i>{@code %xy}</i>" sequences
	 * @return the decoded value
	 * @throws IllegalArgumentException when the given source contains invalid encoded sequences
	 */
	private static String uriDecode(String source, Charset charset) {
		int length = source.length();
		int firstPercentIndex = source.indexOf('%');
		if (length == 0 || firstPercentIndex < 0) {
			return source;
		}

		StringBuilder output = new StringBuilder(length);
		output.append(source, 0, firstPercentIndex);
		byte[] bytes = null;
		int i = firstPercentIndex;
		while (i < length) {
			char ch = source.charAt(i);
			if (ch == '%') {
				try {
					if (bytes == null) {
						bytes = new byte[(length - i) / 3];
					}

					int pos = 0;
					while (i + 2 < length && ch == '%') {
						bytes[pos++] = (byte) HexFormat.fromHexDigits(source, i + 1, i + 3);
						i += 3;
						if (i < length) {
							ch = source.charAt(i);
						}
					}

					if (i < length && ch == '%') {
						throw new IllegalArgumentException("Incomplete trailing escape (%) pattern");
					}

					output.append(new String(bytes, 0, pos, charset));
				}
				catch (NumberFormatException ex) {
					throw new IllegalArgumentException("Invalid encoded sequence \"" + source.substring(i) + "\"");
				}
			}
			else {
				output.append(ch);
				i++;
			}
		}
		return output.toString();
	}

	private boolean isInvalidPath(String path) {
		String pathLowerCase = path.toLowerCase(Locale.ROOT);
		if (pathLowerCase.contains("web-inf") || pathLowerCase.contains("meta-inf")) {
			return true;
		}
		if (path.contains(":/")) {
			String relativePath = (path.charAt(0) == '/' ? path.substring(1) : path);
			if (ResourceUtils.isUrl(relativePath) || relativePath.startsWith("url:")) {
				return true;
			}
		}
		return path.contains("../");
	}

	private boolean isInvalidEncodedInputPath(String path) {
		if (path.contains("%")) {
			try {
				// Use URLDecoder (vs UriUtils) to preserve potentially decoded UTF-8 chars
				String decodedPath = URLDecoder.decode(path, StandardCharsets.UTF_8);
				if (isInvalidPath(decodedPath)) {
					return true;
				}
				decodedPath = processPath(decodedPath);
				if (isInvalidPath(decodedPath)) {
					return true;
				}
			}
			catch (IllegalArgumentException ex) {
				// May not be possible to decode...
			}
		}
		return false;
	}

	private boolean isResourceUnderLocation(Resource resource) throws IOException {
		if (resource.getClass() != this.location.getClass()) {
			return false;
		}

		String resourcePath;
		String locationPath;

		if (resource instanceof UrlResource) {
			resourcePath = resource.getURL().toExternalForm();
			locationPath = StringUtils.cleanPath(this.location.getURL().toString());
		}
		else if (resource instanceof ClassPathResource classPathResource) {
			resourcePath = classPathResource.getPath();
			locationPath = StringUtils.cleanPath(((ClassPathResource) this.location).getPath());
		}
		else if (resource instanceof ServletContextResource servletContextResource) {
			resourcePath = servletContextResource.getPath();
			locationPath = StringUtils.cleanPath(((ServletContextResource) this.location).getPath());
		}
		else {
			resourcePath = resource.getURL().getPath();
			locationPath = StringUtils.cleanPath(this.location.getURL().getPath());
		}

		if (locationPath.equals(resourcePath)) {
			return true;
		}
		locationPath = (locationPath.endsWith("/") || locationPath.isEmpty() ? locationPath : locationPath + "/");
		return (resourcePath.startsWith(locationPath) && !isInvalidEncodedResourcePath(resourcePath));
	}

	private boolean isInvalidEncodedResourcePath(String resourcePath) {
		if (resourcePath.contains("%")) {
			// Use URLDecoder (vs UriUtils) to preserve potentially decoded UTF-8 chars...
			try {
				String decodedPath = URLDecoder.decode(resourcePath, StandardCharsets.UTF_8);
				if (decodedPath.contains("../") || decodedPath.contains("..\\")) {
					return true;
				}
			}
			catch (IllegalArgumentException ex) {
				// May not be possible to decode...
			}
		}
		return false;
	}

	@Override
	public String toString() {
		return this.pattern + " -> " + this.location;
	}

}
