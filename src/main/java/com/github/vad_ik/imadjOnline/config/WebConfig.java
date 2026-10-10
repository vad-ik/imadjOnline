package com.github.vad_ik.imadjOnline.config;

import com.github.vad_ik.imadjOnline.auth.IdentityInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final IdentityInterceptor identityInterceptor;

    public WebConfig(IdentityInterceptor identityInterceptor) {
        this.identityInterceptor = identityInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(identityInterceptor)
                .addPathPatterns("/api/**");
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
        registry.addViewController("/game").setViewName("forward:/game.html");
        registry.addViewController("/profile").setViewName("forward:/profile.html");
    }
}
