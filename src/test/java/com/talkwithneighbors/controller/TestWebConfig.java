/*
 * (이 파일은 더 이상 사용하지 않습니다. 모든 내용을 주석 처리)
 * (이 파일은 static inner class로 대체되어 더 이상 사용되지 않습니다.)
 *
 * import com.talkwithneighbors.security.UserSession;
 * import org.springframework.context.annotation.Configuration;
 * import org.springframework.web.method.support.HandlerMethodArgumentResolver;
 * import org.springframework.web.method.support.ModelAndViewContainer;
 * import org.springframework.web.context.request.NativeWebRequest;
 * import org.springframework.web.bind.support.WebDataBinderFactory;
 * import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
 * import java.util.List;
 *
 * @Configuration
 * public class TestWebConfig implements WebMvcConfigurer {
 *     @Override
 *     public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
 *         System.out.println("[TestWebConfig] addArgumentResolvers 등록됨");
 *         resolvers.add(new HandlerMethodArgumentResolver() {
 *             @Override
 *             public boolean supportsParameter(org.springframework.core.MethodParameter parameter) {
 *                 return parameter.getParameterType().equals(UserSession.class);
 *             }
 *             @Override
 *             public Object resolveArgument(org.springframework.core.MethodParameter parameter, ModelAndViewContainer mavContainer,
 *                                           NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
 *                 System.out.println("[TestWebConfig] UserSession injected by ArgumentResolver!");
 *                 return new UserSession(1L, "testuser", "test@test.com", "testnick");
 *             }
 *         });
 *     }
 * }
 */
