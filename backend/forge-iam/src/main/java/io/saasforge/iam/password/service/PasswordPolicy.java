package io.saasforge.iam.password.service;

import io.saasforge.iam.password.config.PasswordProperties;
import io.saasforge.sdk.core.api.ForgeException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Password Policy（参照 NIST 800-63B）：长度在 Realm 最小长度与 72 字节之间、不在内置弱密码清单中、不与登录标识相同；
 * 不要求字符组合，不要求定期修改。
 *
 * <p>只校验新设置的密码（注册、接受邀请、自助改密码、忘记密码重设、管理员重置 Operator 密码），登录不校验，存量密码不受影响。
 */
@Service
public class PasswordPolicy {
    // 随 iam 打包、启动时加载，不调用外部服务。
    private static final String WEAK_LIST = "password/weak-passwords.txt";

    private final int tenantMinLength;
    private final int platformMinLength;
    private final Set<String> weak;

    PasswordPolicy(PasswordProperties properties) {
        this.tenantMinLength = properties.tenant().minLength();
        this.platformMinLength = properties.platform().minLength();
        this.weak = loadWeakList();
    }

    /**
     * 按 Tenant Realm 的规则校验 Identity 的新密码。
     *
     * @param identifiers 该 Identity 的登录标识（账号、邮箱、手机号），可含 null
     * @throws ForgeException 400 {@code password.*}
     */
    public void checkIdentity(String password, String... identifiers) {
        check(tenantMinLength, password, identifiers);
    }

    /**
     * 按 Platform Realm 的规则校验 Operator 的新密码。
     *
     * @param account Operator 的账号，Operator 只以账号登录
     * @throws ForgeException 400 {@code password.*}
     */
    public void checkOperator(String password, String account) {
        check(platformMinLength, password, account);
    }

    private void check(int minLength, String password, String... identifiers) {
        if (password.codePointCount(0, password.length()) < minLength) {
            throw ForgeException.withErrorMessage(PasswordErrorCode.TOO_SHORT, "Password too short", minLength);
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > PasswordProperties.MAX_BYTES) {
            throw ForgeException.withErrorMessage(
                    PasswordErrorCode.TOO_LONG, "Password too long", PasswordProperties.MAX_BYTES);
        }
        for (String identifier : identifiers) {
            if (identifier != null && identifier.equalsIgnoreCase(password)) {
                throw ForgeException.withErrorMessage(
                        PasswordErrorCode.SAME_AS_IDENTIFIER, "Password equals a login identifier");
            }
        }
        if (weak.contains(password.toLowerCase(Locale.ROOT))) {
            throw ForgeException.withErrorMessage(PasswordErrorCode.TOO_COMMON, "Password is too common");
        }
    }

    private static Set<String> loadWeakList() {
        InputStream stream = PasswordPolicy.class.getClassLoader().getResourceAsStream(WEAK_LIST);
        if (stream == null) throw new IllegalStateException(WEAK_LIST + " is missing from the classpath");
        Set<String> result = new HashSet<>();
        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String entry = line.strip();
                if (!entry.isEmpty() && !entry.startsWith("#")) result.add(entry.toLowerCase(Locale.ROOT));
            }
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        return Set.copyOf(result);
    }
}
