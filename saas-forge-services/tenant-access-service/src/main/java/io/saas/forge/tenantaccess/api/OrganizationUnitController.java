package io.saas.forge.tenantaccess.api;

import io.saas.forge.sdk.tenant.TenantContextAccessor;
import io.saas.forge.tenantaccess.application.organization.OrganizationUnitService;
import io.saas.forge.tenantaccess.contract.api.OrganizationApi;
import io.saas.forge.tenantaccess.contract.model.CreateOrganizationUnitRequest;
import io.saas.forge.tenantaccess.contract.model.MoveOrganizationUnitRequest;
import io.saas.forge.tenantaccess.contract.model.OrganizationUnit;
import io.saas.forge.tenantaccess.contract.model.OrganizationUnitList;
import io.saas.forge.tenantaccess.contract.model.RenameOrganizationUnitRequest;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrganizationUnitController implements OrganizationApi {
    private final TenantContextAccessor contexts;
    private final OrganizationUnitService service;

    public OrganizationUnitController(TenantContextAccessor contexts, OrganizationUnitService service) {
        this.contexts = contexts;
        this.service = service;
    }

    @Override
    public ResponseEntity<OrganizationUnitList> listOrganizationUnits() {
        var context = contexts.requireCurrent();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new OrganizationUnitList(context.tenantId(), service.list(context).stream()
                        .map(OrganizationUnitController::response).toList()));
    }

    @Override
    public ResponseEntity<OrganizationUnit> createOrganizationUnit(CreateOrganizationUnitRequest request) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(response(service.create(contexts.requireCurrent(), request.getName(), request.getParentId())));
    }

    @Override
    public ResponseEntity<OrganizationUnit> renameOrganizationUnit(UUID unitId, RenameOrganizationUnitRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(response(service.rename(contexts.requireCurrent(), unitId, request.getName())));
    }

    @Override
    public ResponseEntity<OrganizationUnit> moveOrganizationUnit(UUID unitId, MoveOrganizationUnitRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(response(service.move(contexts.requireCurrent(), unitId, request.getParentId())));
    }

    @Override
    public ResponseEntity<Void> deleteOrganizationUnit(UUID unitId) {
        service.delete(contexts.requireCurrent(), unitId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private static OrganizationUnit response(io.saas.forge.tenantaccess.application.organization.OrganizationUnit unit) {
        return new OrganizationUnit(unit.id(), unit.name(), unit.memberCount()).parentId(unit.parentId());
    }
}
