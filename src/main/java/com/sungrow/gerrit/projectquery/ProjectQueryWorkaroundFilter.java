package com.sungrow.gerrit.projectquery;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gerrit 3.6.1 project-query pagination workaround.
 *
 * <p>This filter only handles selected GET /projects/ queries. In rewrite mode it changes the
 * downstream value of request parameter {@code n} while leaving all other request parameters
 * unchanged.
 */
public final class ProjectQueryWorkaroundFilter implements Filter {
  private static final Logger log = LoggerFactory.getLogger(ProjectQueryWorkaroundFilter.class);

  private static final String HEADER = "X-Sungrow-Gerrit-ProjectQuery-Workaround";
  private static final Set<String> MATCHED_QUERIES =
      Collections.unmodifiableSet(
          new HashSet<>(
              Arrays.asList("state:active", "state:active OR state:read-only")));

  private volatile Path configPath;

  @Override
  public void init(FilterConfig filterConfig) {
    String configuredPath = filterConfig.getInitParameter("configPath");
    if (configuredPath == null || configuredPath.trim().isEmpty()) {
      configPath = null;
      log.warn(
          "ProjectQueryWorkaroundFilter loaded without init parameter configPath; filter stays OFF");
      return;
    }

    configPath = Paths.get(configuredPath.trim());
    log.info("ProjectQueryWorkaroundFilter loaded, configPath={}", configPath);
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    if (!(request instanceof HttpServletRequest)) {
      chain.doFilter(request, response);
      return;
    }

    HttpServletRequest httpRequest = (HttpServletRequest) request;

    if (!isCandidateRequest(httpRequest)) {
      chain.doFilter(request, response);
      return;
    }

    RuntimeConfig config = loadConfig();
    if (config.mode == Mode.OFF) {
      chain.doFilter(request, response);
      return;
    }

    MatchResult match = match(httpRequest, config);
    if (!match.matched) {
      chain.doFilter(request, response);
      return;
    }

    String headerValue =
        config.mode.value + ";n=" + match.originalN + "->" + config.targetN;
    if (config.responseHeader && response instanceof HttpServletResponse) {
      ((HttpServletResponse) response).setHeader(HEADER, headerValue);
    }

    log.info(
        "ProjectQueryWorkaround match mode={} uri={} user={} query=\"{}\" S={} n={} targetN={}",
        config.mode.value,
        httpRequest.getRequestURI(),
        safeValue(httpRequest.getRemoteUser()),
        safeValue(httpRequest.getParameter("query")),
        match.offset,
        match.originalN,
        config.targetN);

    if (config.mode == Mode.DRY_RUN) {
      chain.doFilter(request, response);
      return;
    }

    HttpServletRequest rewritten =
        new SingleParameterRequestWrapper(httpRequest, "n", Integer.toString(config.targetN));
    chain.doFilter(rewritten, response);
  }

  @Override
  public void destroy() {
    // Nothing to release.
  }

  private boolean isCandidateRequest(HttpServletRequest request) {
    if (!"GET".equalsIgnoreCase(request.getMethod())) {
      return false;
    }

    String uri = request.getRequestURI();
    if (uri == null) {
      return false;
    }

    return uri.endsWith("/projects/") || uri.endsWith("/projects");
  }

  private MatchResult match(HttpServletRequest request, RuntimeConfig config) {
    String query = request.getParameter("query");
    if (!MATCHED_QUERIES.contains(query)) {
      return MatchResult.noMatch();
    }

    Integer originalN = parseInteger(request.getParameter("n"));
    if (originalN == null || originalN <= 0 || originalN > config.maxOriginalN) {
      return MatchResult.noMatch();
    }

    String offsetParam = request.getParameter("S");
    Integer offset = offsetParam == null ? Integer.valueOf(0) : parseInteger(offsetParam);
    if (offset == null || offset.intValue() != config.matchOffset) {
      return MatchResult.noMatch();
    }

    return MatchResult.matched(originalN.intValue(), offset.intValue());
  }

  private RuntimeConfig loadConfig() {
    Path path = configPath;
    if (path == null) {
      return RuntimeConfig.off();
    }

    Properties properties = new Properties();
    try (InputStream in = Files.newInputStream(path)) {
      properties.load(in);
    } catch (IOException e) {
      log.warn("Cannot read workaround config {}; filter stays OFF: {}", path, e.toString());
      return RuntimeConfig.off();
    }

    Mode mode = Mode.from(properties.getProperty("mode", "off"));
    int targetN = positiveInt(properties.getProperty("targetN"), 100);
    int maxOriginalN = positiveInt(properties.getProperty("maxOriginalN"), 99);
    int matchOffset = nonNegativeInt(properties.getProperty("matchOffset"), 0);
    boolean responseHeader =
        Boolean.parseBoolean(properties.getProperty("responseHeader", "true").trim());

    if (targetN <= 0 || maxOriginalN <= 0 || matchOffset < 0) {
      log.warn("Invalid workaround config in {}; filter stays OFF", path);
      return RuntimeConfig.off();
    }

    return new RuntimeConfig(mode, targetN, maxOriginalN, matchOffset, responseHeader);
  }

  private static Integer parseInteger(String value) {
    if (value == null) {
      return null;
    }
    try {
      return Integer.valueOf(value.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static int positiveInt(String value, int defaultValue) {
    Integer parsed = parseInteger(value);
    return parsed != null && parsed.intValue() > 0 ? parsed.intValue() : defaultValue;
  }

  private static int nonNegativeInt(String value, int defaultValue) {
    Integer parsed = parseInteger(value);
    return parsed != null && parsed.intValue() >= 0 ? parsed.intValue() : defaultValue;
  }

  private static String safeValue(String value) {
    return value == null ? "-" : value;
  }

  private enum Mode {
    OFF("off"),
    DRY_RUN("dry-run"),
    REWRITE("rewrite");

    private final String value;

    Mode(String value) {
      this.value = value;
    }

    static Mode from(String value) {
      String normalized = value == null ? "off" : value.trim().toLowerCase();
      for (Mode mode : values()) {
        if (mode.value.equals(normalized)) {
          return mode;
        }
      }
      return OFF;
    }
  }

  private static final class RuntimeConfig {
    private final Mode mode;
    private final int targetN;
    private final int maxOriginalN;
    private final int matchOffset;
    private final boolean responseHeader;

    private RuntimeConfig(
        Mode mode, int targetN, int maxOriginalN, int matchOffset, boolean responseHeader) {
      this.mode = mode;
      this.targetN = targetN;
      this.maxOriginalN = maxOriginalN;
      this.matchOffset = matchOffset;
      this.responseHeader = responseHeader;
    }

    private static RuntimeConfig off() {
      return new RuntimeConfig(Mode.OFF, 100, 99, 0, true);
    }
  }

  private static final class MatchResult {
    private final boolean matched;
    private final int originalN;
    private final int offset;

    private MatchResult(boolean matched, int originalN, int offset) {
      this.matched = matched;
      this.originalN = originalN;
      this.offset = offset;
    }

    private static MatchResult noMatch() {
      return new MatchResult(false, -1, -1);
    }

    private static MatchResult matched(int originalN, int offset) {
      return new MatchResult(true, originalN, offset);
    }
  }

  private static final class SingleParameterRequestWrapper extends HttpServletRequestWrapper {
    private final String parameterName;
    private final String parameterValue;

    private SingleParameterRequestWrapper(
        HttpServletRequest request, String parameterName, String parameterValue) {
      super(request);
      this.parameterName = parameterName;
      this.parameterValue = parameterValue;
    }

    @Override
    public String getParameter(String name) {
      if (parameterName.equals(name)) {
        return parameterValue;
      }
      return super.getParameter(name);
    }

    @Override
    public String[] getParameterValues(String name) {
      if (parameterName.equals(name)) {
        return new String[] {parameterValue};
      }
      return super.getParameterValues(name);
    }

    @Override
    public Map<String, String[]> getParameterMap() {
      Map<String, String[]> rewritten = new HashMap<>();
      for (Map.Entry<String, String[]> entry : super.getParameterMap().entrySet()) {
        String[] values = entry.getValue();
        rewritten.put(
            entry.getKey(), values == null ? null : Arrays.copyOf(values, values.length));
      }
      rewritten.put(parameterName, new String[] {parameterValue});
      return Collections.unmodifiableMap(rewritten);
    }

    @Override
    public Enumeration<String> getParameterNames() {
      return Collections.enumeration(getParameterMap().keySet());
    }
  }
}
