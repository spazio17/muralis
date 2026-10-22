/*
 * Copyright 2026 Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import android.content.Context;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Adding, changing, switching and removing an integration, and trying one before it is saved:
 * the one way both settings surfaces do it, so the panel and the web admin refuse the same
 * things with the same words.
 */
final class IntegrationSettings {
    private IntegrationSettings() {
    }

    /** What a save did: why it was refused, or the id of the instance it saved. */
    static final class Saved {
        final String problem;
        final String id;

        private Saved(String problem, String id) {
            this.problem = problem;
            this.id = id;
        }
    }

    /**
     * Adds an instance of {@code definitionId} ({@code instanceId} empty) or changes one. The
     * values hold every field by id; an empty secret keeps the stored one, as every password box
     * does.
     */
    static Saved save(Context context, String instanceId, String definitionId, String name,
            Map<String, String> values) {
        Integrations.Definition definition = Integrations.DEFINITIONS.get(definitionId);
        if (definition == null) {
            return new Saved("this version of Muralis has no integration called "
                    + definitionId, null);
        }
        String blocked = editionProblem(context, definition);
        if (blocked != null) {
            return new Saved(blocked, null);
        }
        String trimmed = name == null ? "" : name.trim();
        String nameProblem = Integrations.checkName(trimmed);
        if (nameProblem != null) {
            return new Saved(nameProblem, null);
        }
        SecretStore store = new SecretStore(KioskConfig.storageContext(context));
        synchronized (KioskConfig.INTEGRATIONS_STORE) {
            List<Integrations.Instance> stored = KioskConfig.integrationsOf(context);
            Integrations.Instance old = null;
            Set<String> taken = new HashSet<>();
            for (Integrations.Instance one : stored) {
                taken.add(one.id);
                if (one.id.equals(instanceId)) {
                    old = one;
                }
            }
            boolean fresh = instanceId == null || instanceId.isEmpty();
            if (!fresh && old == null) {
                return new Saved("that integration was removed meanwhile", null);
            }
            if (!fresh && !old.definition.equals(definitionId)) {
                return new Saved("that integration is of another kind", null);
            }
            if (fresh && stored.size() >= Integrations.MAX_INSTANCES) {
                return new Saved("a panel holds at most " + Integrations.MAX_INSTANCES
                        + " integrations", null);
            }
            String id = fresh ? Integrations.newInstanceId(definitionId, taken) : old.id;
            Map<String, String> settings = new LinkedHashMap<>();
            Map<String, String> secrets = new LinkedHashMap<>();
            Set<String> kept = new HashSet<>();
            for (Integrations.Field field : definition.fields) {
                String value = values.getOrDefault(field.id, "");
                value = value == null ? "" : value.trim();
                if (field.secret) {
                    if (!value.isEmpty()) {
                        secrets.put(field.id, value);
                    } else if (!fresh && store.has(Integrations.secretName(id, field.id))) {
                        kept.add(field.id);
                    }
                } else {
                    settings.put(field.id, value);
                }
            }
            for (String key : values.keySet()) {
                if (definition.field(key) == null) {
                    return new Saved(definition.name + " has no setting called " + key, null);
                }
            }
            Map<String, String> checked = new LinkedHashMap<>(settings);
            checked.putAll(secrets);
            String problem = Integrations.check(definition, checked, kept);
            if (problem != null) {
                return new Saved(problem, null);
            }
            for (Map.Entry<String, String> secret : secrets.entrySet()) {
                store.put(Integrations.secretName(id, secret.getKey()), secret.getValue());
            }
            Integrations.Instance next = new Integrations.Instance(id, definitionId, trimmed,
                    fresh || old.enabled, settings);
            List<Integrations.Instance> list = new java.util.ArrayList<>();
            boolean replaced = false;
            for (Integrations.Instance one : stored) {
                if (one.id.equals(id)) {
                    list.add(next);
                    replaced = true;
                } else {
                    list.add(one);
                }
            }
            if (!replaced) {
                list.add(next);
            }
            KioskConfig.storeIntegrations(context, list);
            KioskService.refreshIntegrationsSoon(context, secrets.isEmpty() ? null : id);
            // A change made here goes to the fleet when the fleet shares the integrations.
            KioskService.publishTelemetrySoon(context);
            return new Saved(null, id);
        }
    }

    /** Switches an instance on or off; null when done, else why not. */
    static String setEnabled(Context context, String instanceId, boolean on) {
        synchronized (KioskConfig.INTEGRATIONS_STORE) {
            List<Integrations.Instance> stored = KioskConfig.integrationsOf(context);
            List<Integrations.Instance> list = new java.util.ArrayList<>();
            boolean found = false;
            for (Integrations.Instance one : stored) {
                if (one.id.equals(instanceId)) {
                    Integrations.Definition definition =
                            Integrations.DEFINITIONS.get(one.definition);
                    if (on && definition == null) {
                        return "this version of Muralis cannot run it";
                    }
                    String blocked = on ? editionProblem(context, definition) : null;
                    if (blocked != null) {
                        return blocked;
                    }
                    list.add(one.withEnabled(on));
                    found = true;
                } else {
                    list.add(one);
                }
            }
            if (!found) {
                return "that integration was removed meanwhile";
            }
            KioskConfig.storeIntegrations(context, list);
        }
        KioskService.refreshIntegrationsSoon(context, null);
        KioskService.publishTelemetrySoon(context);
        return null;
    }

    /**
     * Removes an instance and its secrets. Its values leave the automations and MQTT;
     * a rule that waited for one stays, and never fires again until it is changed.
     */
    static String remove(Context context, String instanceId) {
        synchronized (KioskConfig.INTEGRATIONS_STORE) {
            List<Integrations.Instance> stored = KioskConfig.integrationsOf(context);
            List<Integrations.Instance> list = new java.util.ArrayList<>();
            Integrations.Instance gone = null;
            for (Integrations.Instance one : stored) {
                if (one.id.equals(instanceId)) {
                    gone = one;
                } else {
                    list.add(one);
                }
            }
            if (gone == null) {
                return "that integration was removed meanwhile";
            }
            KioskConfig.storeIntegrations(context, list);
            Integrations.Definition definition = Integrations.DEFINITIONS.get(gone.definition);
            SecretStore store = new SecretStore(KioskConfig.storageContext(context));
            if (definition != null) {
                for (Integrations.Field field : definition.fields) {
                    if (field.secret) {
                        store.clear(Integrations.secretName(gone.id, field.id));
                    }
                }
            }
        }
        KioskService.refreshIntegrationsSoon(context, null);
        KioskService.publishTelemetrySoon(context);
        return null;
    }

    /**
     * The instances as the fleet shares them: every setting a definition marks SHARED, none of
     * the PANEL ones (a room is each panel's own), and no secret, which travels apart.
     */
    static List<Integrations.Instance> sharedPart(List<Integrations.Instance> instances) {
        List<Integrations.Instance> shared = new java.util.ArrayList<>();
        for (Integrations.Instance instance : instances) {
            Integrations.Definition definition = Integrations.DEFINITIONS.get(instance.definition);
            Map<String, String> settings = new LinkedHashMap<>();
            for (Map.Entry<String, String> setting : instance.settings.entrySet()) {
                Integrations.Field field = definition == null ? null
                        : definition.field(setting.getKey());
                if (field == null || field.scope == Integrations.Scope.SHARED) {
                    settings.put(setting.getKey(), setting.getValue());
                }
            }
            shared.add(new Integrations.Instance(instance.id, instance.definition, instance.name,
                    instance.enabled, settings));
        }
        return shared;
    }

    /**
     * The SHARED secret settings of every instance, for the fleet's wire only: "{}" when there
     * are none, and "" while one cannot be read (before the first unlock), which the fleet's
     * sync reads as "keep what the others hold", never as "none".
     */
    static String sharedSecrets(Context context) {
        SecretStore store = new SecretStore(KioskConfig.storageContext(context));
        Map<String, Object> all = new LinkedHashMap<>();
        for (Integrations.Instance instance : KioskConfig.integrationsOf(context)) {
            Integrations.Definition definition = Integrations.DEFINITIONS.get(instance.definition);
            if (definition == null) {
                continue;
            }
            for (Integrations.Field field : definition.fields) {
                if (!field.secret || field.scope != Integrations.Scope.SHARED) {
                    continue;
                }
                String value = store.getOrNull(Integrations.secretName(instance.id, field.id));
                if (value == null) {
                    return "";
                }
                if (!value.isEmpty()) {
                    all.put(instance.id + "_" + field.id, value);
                }
            }
        }
        return TinyJson.write(all);
    }

    /**
     * Applies the fleet's integrations on this panel: the fleet's list becomes this panel's, each
     * instance checked as its own Save checks it, a PANEL setting kept from this panel's own
     * instance of the same id, the shared secrets stored, and the secrets of an instance the
     * fleet no longer has cleared. Returns the refusals, one line each; a refused instance keeps
     * this panel's own. An empty secret block keeps the stored secrets (they are blanked in
     * every stored copy of the fleet's list).
     */
    static List<String> applyShared(Context context, String instancesJson, String secretsJson) {
        List<String> refused = new java.util.ArrayList<>();
        List<Integrations.Instance> fleet = Integrations.parseInstances(instancesJson);
        SecretStore store = new SecretStore(KioskConfig.storageContext(context));
        Map<String, Object> secrets = null;
        if (secretsJson != null && !secretsJson.isEmpty()) {
            try {
                secrets = TinyJson.object(TinyJson.parse(secretsJson));
            } catch (IllegalArgumentException unreadable) {
                refused.add("integration_secrets: not readable");
            }
        }
        synchronized (KioskConfig.INTEGRATIONS_STORE) {
            List<Integrations.Instance> own = KioskConfig.integrationsOf(context);
            Map<String, Integrations.Instance> ownById = new LinkedHashMap<>();
            for (Integrations.Instance instance : own) {
                ownById.put(instance.id, instance);
            }
            List<Integrations.Instance> next = new java.util.ArrayList<>();
            for (Integrations.Instance shared : fleet) {
                Integrations.Instance mine = ownById.get(shared.id);
                Integrations.Definition definition =
                        Integrations.DEFINITIONS.get(shared.definition);
                Map<String, String> settings = new LinkedHashMap<>(shared.settings);
                if (definition != null && mine != null) {
                    for (Integrations.Field field : definition.fields) {
                        if (field.scope == Integrations.Scope.PANEL && !field.secret) {
                            settings.put(field.id, mine.settings.getOrDefault(field.id, ""));
                        }
                    }
                }
                Integrations.Instance merged = new Integrations.Instance(shared.id,
                        shared.definition, shared.name, shared.enabled, settings);
                if (definition != null) {
                    java.util.Set<String> secretFields = new java.util.HashSet<>();
                    for (Integrations.Field field : definition.fields) {
                        if (field.secret) {
                            secretFields.add(field.id);
                        }
                    }
                    String problem = Integrations.checkName(merged.name);
                    if (problem == null) {
                        problem = Integrations.check(definition, settings, secretFields);
                    }
                    if (problem == null && merged.enabled) {
                        problem = editionProblem(context, definition);
                    }
                    if (problem != null) {
                        refused.add("integrations: " + merged.name + ": " + problem);
                        if (mine != null) {
                            next.add(mine);
                        }
                        continue;
                    }
                }
                next.add(merged);
            }
            java.util.Set<String> kept = new java.util.HashSet<>();
            for (Integrations.Instance instance : next) {
                kept.add(instance.id);
            }
            for (Integrations.Instance gone : own) {
                if (kept.contains(gone.id)) {
                    continue;
                }
                Integrations.Definition definition = Integrations.DEFINITIONS.get(gone.definition);
                if (definition != null) {
                    for (Integrations.Field field : definition.fields) {
                        if (field.secret) {
                            store.clear(Integrations.secretName(gone.id, field.id));
                        }
                    }
                }
            }
            if (secrets != null) {
                for (Map.Entry<String, Object> one : secrets.entrySet()) {
                    String key = one.getKey();
                    int cut = key.indexOf('_');
                    if (cut > 0 && kept.contains(key.substring(0, cut))
                            && one.getValue() instanceof String) {
                        store.put("integration_" + key, (String) one.getValue());
                    }
                }
            }
            KioskConfig.storeIntegrations(context, next);
        }
        KioskService.refreshIntegrationsSoon(context, null);
        return refused;
    }

    /** What removing an integration changes, the words both surfaces ask with. */
    static final String REMOVED = "Its values leave the automations and MQTT. Rules"
            + " that wait for them stay, and do not run until they are changed.";

    /**
     * Asks the service once with the settings in the boxes, before anything is stored: the
     * answer in words, "Answered: 512 W", or why it did not. Blocks for up to the request's
     * timeouts, so never on the main thread. An empty secret box tries the stored secret.
     */
    static String test(Context context, String instanceId, String definitionId,
            Map<String, String> values) {
        Integrations.Definition definition = Integrations.DEFINITIONS.get(definitionId);
        if (definition == null || definition.source == null) {
            return "Not tried: this version of Muralis cannot run it.";
        }
        Map<String, String> settings = new LinkedHashMap<>();
        Map<String, String> secrets = new LinkedHashMap<>();
        Set<String> kept = new HashSet<>();
        SecretStore store = new SecretStore(KioskConfig.storageContext(context));
        for (Integrations.Field field : definition.fields) {
            String value = values.getOrDefault(field.id, "");
            value = value == null ? "" : value.trim();
            if (field.secret) {
                if (value.isEmpty() && instanceId != null && !instanceId.isEmpty()) {
                    String storedSecret = store.getOrNull(
                            Integrations.secretName(instanceId, field.id));
                    if (storedSecret == null) {
                        return "Not tried: " + field.label + " cannot be read yet.";
                    }
                    if (!storedSecret.isEmpty()) {
                        kept.add(field.id);
                        secrets.put(field.id, storedSecret);
                    }
                } else if (!value.isEmpty()) {
                    secrets.put(field.id, value);
                }
            } else {
                settings.put(field.id, value);
            }
        }
        Map<String, String> checked = new LinkedHashMap<>(settings);
        for (Map.Entry<String, String> secret : secrets.entrySet()) {
            if (!kept.contains(secret.getKey())) {
                checked.put(secret.getKey(), secret.getValue());
            }
        }
        String problem = Integrations.check(definition, checked, kept);
        if (problem != null) {
            return "Not tried: " + problem + ".";
        }
        Integrations.Instance trial = new Integrations.Instance("trial", definitionId, "Trial",
                true, settings);
        Integrations.Result result;
        try {
            result = definition.source.fetch(definition, trial, secrets, IntegrationRunner::get);
        } catch (RuntimeException broken) {
            return "Not answering: the integration failed in the app.";
        }
        if (result.problem != null) {
            return "Not answering: " + result.problem + ".";
        }
        StringBuilder answer = new StringBuilder("Answered: ");
        boolean first = true;
        for (Integrations.ValueSpec spec : definition.valuesOf(trial)) {
            Integrations.Value value = result.values.get(spec.id);
            if (!first) {
                answer.append(", ");
            }
            first = false;
            if (definition.valuesOf(trial).size() > 1) {
                answer.append(spec.label).append(' ');
            }
            answer.append(describe(value, spec));
        }
        return answer.append('.').toString();
    }

    /** A value in words, as a row shows it. */
    static String describe(Integrations.Value value, Integrations.ValueSpec spec) {
        if (value == null || !value.available) {
            return "unknown";
        }
        switch (value.type) {
            case FLAG:
                return value.flag ? "on" : "off";
            case TEXT:
                return "\"" + value.text + "\"";
            case TIME:
                return new java.util.Date((long) value.number).toString();
            default: {
                double number = value.number;
                String figure = number == Math.rint(number) && Math.abs(number) < 1e15
                        ? Long.toString((long) number) : Double.toString(number);
                return spec.unit.isEmpty() ? figure : figure + " " + spec.unit;
            }
        }
    }

    /** Why this panel's edition cannot run the definition, or null. */
    static String editionProblem(Context context, Integrations.Definition definition) {
        if ("pro".equals(definition.edition) && !ProEntitlement.isActive(context)) {
            return definition.name + " needs Muralis Pro";
        }
        if ("business".equals(definition.edition)) {
            return definition.name + " needs Muralis Business";
        }
        return null;
    }
}
