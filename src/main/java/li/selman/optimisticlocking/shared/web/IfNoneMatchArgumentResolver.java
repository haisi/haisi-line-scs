package li.selman.optimisticlocking.shared.web;

import li.selman.optimisticlocking.shared.IfNoneMatch;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolves an {@link IfNoneMatch} controller parameter straight from the raw {@code If-None-Match}
 * header.
 */
public class IfNoneMatchArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return IfNoneMatch.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            @Nullable ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            @Nullable WebDataBinderFactory binderFactory) {
        return IfNoneMatch.of(webRequest.getHeader(HttpHeaders.IF_NONE_MATCH));
    }
}
