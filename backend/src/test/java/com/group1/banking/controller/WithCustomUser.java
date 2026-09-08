package com.group1.banking.controller;

import com.group1.banking.entity.User;
import com.group1.banking.enums.RoleName;
import com.group1.banking.security.CustomUserPrincipal;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.UUID;

import org.springframework.security.test.context.support.WithSecurityContext;

/**
 * Custom annotation to provide a {@link CustomUserPrincipal} in the security context for tests.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@WithSecurityContext(factory = WithCustomUser.Factory.class)
public @interface WithCustomUser {

    long customerId() default 42L;

    public static class Factory implements WithSecurityContextFactory<WithCustomUser> {
        @Override
        public SecurityContext createSecurityContext(WithCustomUser annotation) {
            User user = new User();
            user.setUserId(UUID.randomUUID());
            user.setUsername("test@example.com");
            user.setPasswordHash("hash");
            user.setRoles(List.of(RoleName.RETAIL_CUSTOMER));
            user.setActive(true);
            user.setCustomerId(annotation.customerId());

            CustomUserPrincipal principal = new CustomUserPrincipal(user);
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            return context;
        }
    }
}
