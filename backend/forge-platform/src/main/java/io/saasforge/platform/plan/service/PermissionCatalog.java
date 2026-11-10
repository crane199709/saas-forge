package io.saasforge.platform.plan.service;

import io.saasforge.platform.plan.model.CatalogPermission;
import io.saasforge.platform.plan.model.CatalogPermissionTree;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.core.api.ForgeException;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 从 iam 读取 Feature Pack 可选的 Permission Code（各业务服务 Resource Manifest 中当前有效的权限码）。
 *
 * <p>每次直接读取而不缓存：编辑套餐是低频操作，读到的应是业务服务刚上报的清单。
 */
@Service
public class PermissionCatalog {
    private static final ParameterizedTypeReference<ApiResponse<List<CatalogPermission>>> RESPONSE =
            new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<ApiResponse<CatalogPermissionTree>> TREE =
            new ParameterizedTypeReference<>() {};

    private final RestClient client;

    PermissionCatalog(@Qualifier("iamRestClient") RestClient client) {
        this.client = client;
    }

    /**
     * 可纳入 Feature Pack 的权限码，按服务、分组、编码排序。
     *
     * @throws ForgeException 503 {@code plan.catalogUnavailable} iam 不可用或拒绝请求
     */
    public List<CatalogPermission> permissions() {
        return read("/internal/permissions", RESPONSE);
    }

    /**
     * Feature Pack 的菜单授权树，菜单不按 Plan 或 Sub-App 裁剪。
     *
     * @throws ForgeException 503 {@code plan.catalogUnavailable} iam 不可用或拒绝请求
     */
    public CatalogPermissionTree tree() {
        return read("/internal/permission-tree", TREE);
    }

    private <T> T read(String uri, ParameterizedTypeReference<ApiResponse<T>> type) {
        try {
            ApiResponse<T> response = client.get().uri(uri).retrieve().body(type);
            if (response == null || response.data() == null) {
                throw ForgeException.withErrorMessage(PlanErrorCode.CATALOG_UNAVAILABLE, "IAM returned no catalog");
            }
            return response.data();
        } catch (RestClientException error) {
            throw ForgeException.withErrorMessage(
                    PlanErrorCode.CATALOG_UNAVAILABLE, "Failed to read permission catalog: " + error.getMessage());
        }
    }
}
