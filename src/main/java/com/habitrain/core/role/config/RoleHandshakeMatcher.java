package com.habitrain.core.role.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Pure comparison of the server manifest against the client's local manifest
 * (fix-doc §14.2). No game or network types are referenced, so the matrix is
 * unit-testable:
 *
 * <table>
 *   <tr><td>API version differs</td><td>{@code REJECTED_MISSING_PROVIDER}</td></tr>
 *   <tr><td>Required provider/version missing</td><td>{@code REJECTED_MISSING_PROVIDER}</td></tr>
 *   <tr><td>Gameplay definition hash differs</td><td>{@code HASH_MISMATCH}</td></tr>
 *   <tr><td>Presentation differs + client lacks resources</td><td>{@code DEGRADED_CLIENT_EXTENSION}</td></tr>
 *   <tr><td>Everything matches</td><td>{@code OK}</td></tr>
 * </table>
 *
 * <p>Priority is by severity: required-provider rejection first (needs the most
 * concrete remediation), then gameplay hash, then presentation degradation. A
 * nullable client hash means "trust the server" so a client that cannot compute
 * an independent fingerprint never false-positives. Since audit P1-4 a
 * required provider must be present in BOTH the version map and the client's
 * loaded client-extension set (its {@code requiresClient()} declaration is
 * explicit, not guessed from entrypoint presence).
 *
 * <p><b>Hash branch caveat (review 2026-08-14 P1):</b> the definition-hash
 * branch only fires when the client reports a non-blank
 * {@code expectedDefinitionHash}. The core client cannot independently compute
 * the server's definition hash (it folds the server-side compiled entry view +
 * the applied {@code roleExtensionsV2} config), so it reports {@code null} and
 * the HASH_MISMATCH branch never triggers for it; the gate instead relies on
 * report completeness, API compatibility and required-provider checks. Do not
 * advertise definition-hash gating as closed for the stock client.
 */
public final class RoleHandshakeMatcher {

    private RoleHandshakeMatcher() {}

    public static RoleHandshakeResult match(RoleManifest server, ClientManifest local) {
        if (server == null || local == null) {
            return RoleHandshakeResult.hashMismatch("缺少服务端或客户端 manifest，无法完成握手");
        }
        if (!sameApiVersion(server.coreApiVersion(), local.coreApiVersion())) {
            return RoleHandshakeResult.rejected(List.of(),
                    "角色扩展 API 版本不匹配：服务端 " + server.coreApiVersion()
                            + " ≠ 客户端 " + local.coreApiVersion()
                            + "。请将客户端模组更新到与服务端相同版本。");
        }

        List<String> missing = missingRequiredProviders(server, local);
        if (!missing.isEmpty()) {
            return RoleHandshakeResult.rejected(missing,
                    "缺少必需的角色扩展 provider/版本：" + String.join("，", missing)
                            + "。请安装对应模组或更新版本后重新加入。");
        }

        if (local.expectedDefinitionHash() != null && !local.expectedDefinitionHash().isBlank()
                && !server.definitionHash().equals(local.expectedDefinitionHash())) {
            return RoleHandshakeResult.hashMismatch(
                    "角色定义哈希不一致（服务端 " + server.definitionHash()
                            + " ≠ 客户端 " + local.expectedDefinitionHash()
                            + "）。请确认服务器与客户端模组版本一致，且服务端角色扩展配置未被修改。");
        }

        if (!local.hasPresentationResources() && local.expectedPresentationHash() != null
                && !local.expectedPresentationHash().isBlank()
                && !server.presentationHash().equals(local.expectedPresentationHash())) {
            return RoleHandshakeResult.degraded(
                    "客户端缺少部分角色展示资源（presentation hash 不一致），进入只读观战模式。");
        }
        return RoleHandshakeResult.ok();
    }

    private static boolean sameApiVersion(String server, String client) {
        if (server == null || client == null) {
            return false;
        }
        String s = server.trim();
        String c = client.trim();
        // Accept exact or major.minor equal (patch drift is fine within a release).
        return s.equals(c) || majorMinor(s).equals(majorMinor(c));
    }

    private static String majorMinor(String version) {
        int dot = version.indexOf('.');
        if (dot < 0) {
            return version;
        }
        int end = version.indexOf('.', dot + 1);
        return end < 0 ? version : version.substring(0, end);
    }

    private static List<String> missingRequiredProviders(RoleManifest server, ClientManifest local) {
        List<String> missing = new ArrayList<>();
        Set<String> loadedExtensions = local.localClientExtensionProviders();
        for (RoleProviderManifest provider : server.providers()) {
            if (!provider.requiredClient()) {
                continue;
            }
            String localVersion = local.localProviderVersions().get(provider.providerId());
            if (localVersion == null) {
                missing.add(provider.providerId() + "（缺失）");
            } else if (provider.version() != null && !provider.version().isBlank()
                    && !provider.version().equals(localVersion)) {
                missing.add(provider.providerId() + "（版本 " + localVersion + " ≠ 服务端 " + provider.version() + "）");
            } else if (!loadedExtensions.contains(provider.providerId())) {
                // Audit P1-4: requiredClient is an explicit declaration, so the
                // provider's client extensions must actually be loaded, not just
                // the mod present. Fail closed.
                missing.add(provider.providerId() + "（已安装，但客户端扩展未加载）");
            }
        }
        return missing;
    }
}