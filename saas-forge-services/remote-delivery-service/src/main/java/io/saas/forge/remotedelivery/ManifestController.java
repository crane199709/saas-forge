package io.saas.forge.remotedelivery;

import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import io.saas.forge.remotedelivery.contract.api.RemoteManifestsApi;
import io.saas.forge.remotedelivery.contract.model.EnabledRemoteManifest;
import io.saas.forge.remotedelivery.contract.model.EnabledRemoteManifestPage;
import io.saas.forge.remotedelivery.contract.model.RemoteManifestPage;
import tools.jackson.databind.ObjectMapper;

@RestController
final class ManifestController implements RemoteManifestsApi {
    private final ManifestAuthority authority;
    private final ManifestService manifests;
    private final jakarta.servlet.http.HttpServletRequest request;
    private final ObjectMapper json;
    ManifestController(ManifestAuthority authority, ManifestService manifests,
            jakarta.servlet.http.HttpServletRequest request,ObjectMapper json) {
        this.authority = authority; this.manifests = manifests; this.request = request; this.json = json;
    }
    @Override
    public ResponseEntity<io.saas.forge.remotedelivery.contract.model.RemoteManifest> registerRemoteManifest(
            io.saas.forge.remotedelivery.contract.model.RegisterRemoteManifestRequest body) {
        var declaration = new RegisterManifestRequest(body.getModule(),body.getVersion(),body.getSource().toString(),body.getUiVersion(),body.getEntrySha256());
        return response(manifests.register(authority.ci(),declaration));
    }
    @Override
    public ResponseEntity<RemoteManifestPage> listRemoteManifests(String cursor,Integer limit) {
        var actor = authority.administrator(request.getHeader("Authorization"));
        var page = manifests.list(cursor,limit,actor.toString(),false);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                new RemoteManifestPage(page.items().stream().map(this::model).toList(),page.nextCursor(),page.hasMore()));
    }
    @Override
    public ResponseEntity<io.saas.forge.remotedelivery.contract.model.RemoteManifest> approveRemoteManifest(UUID id,UUID key) {
        return decide(id,key,"APPROVE");
    }
    @Override
    public ResponseEntity<io.saas.forge.remotedelivery.contract.model.RemoteManifest> rejectRemoteManifest(UUID id,UUID key) {
        return decide(id,key,"REJECT");
    }
    @Override
    public ResponseEntity<io.saas.forge.remotedelivery.contract.model.RemoteManifest> enableRemoteManifest(UUID id,UUID key) {
        return decide(id,key,"ENABLE");
    }
    @Override
    public ResponseEntity<EnabledRemoteManifestPage> listEnabledRemoteManifests(String cursor,Integer limit) {
        var page = manifests.list(cursor,limit,authority.tenantScope(),true);
        // Shell 发现入口只需要声明，不返回平台管理员的身份与审批事实。
        var items = page.items().stream().map(value -> new EnabledRemoteManifest(value.id(),value.module(),value.version(),
                value.source(),value.uiVersion(),value.entrySha256())).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new EnabledRemoteManifestPage(items,page.nextCursor(),page.hasMore()));
    }
    private ResponseEntity<io.saas.forge.remotedelivery.contract.model.RemoteManifest> decide(UUID id,UUID key,String action) {
        return response(manifests.decide(authority.administrator(request.getHeader("Authorization")),key,id,action));
    }
    private ResponseEntity<io.saas.forge.remotedelivery.contract.model.RemoteManifest> response(RemoteManifest value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(model(value));
    }
    private io.saas.forge.remotedelivery.contract.model.RemoteManifest model(RemoteManifest value) {
        return json.convertValue(value,io.saas.forge.remotedelivery.contract.model.RemoteManifest.class);
    }
}
