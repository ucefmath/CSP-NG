import { defineMessages } from '../define';

export const cacheex = defineMessages(
  {
    // Reasons
    reasonLoop: 'loop',
    reasonHop: 'hop',
    reasonChecksum: 'checksum',
    reasonFiltered: 'filtered',
    reasonInvalid: 'invalid',
    reasonQueue: 'queue',
    reasonFlood: 'flood',
    reasonQuarantine: 'quarantine',
    reasonQuorum: 'quorum (pending/divergent)',
    unknown: 'unknown',

    // Direction hints
    dirOutMode2: 'we receive from server',
    dirOutMode3: 'we send to server',
    dirInMode2: 'we send to client',
    dirInMode3: 'we receive from client',

    // Directions
    dirOutbound: 'outbound',
    dirInbound: 'inbound',

    // State
    connected: 'connected',

    // CacheExPage
    title: 'CacheEx Stats by Peer',
    title404: 'CacheEx Stats',
    endpointUnavailable: "Endpoint unavailable on this backend (404) — feature delivered by the backend agent.",
    refresh: 'Refresh',
    resetCounters: 'Reset counters',
    toastResetOk: 'CacheEx counters reset',
    loading: 'Loading CacheEx stats…',
    summary: "Generated: {generated}. Asymmetric modes: mode 2 = push receive, mode 3 = push send. A pair without traffic does not appear (never fabricated zeros).",
    noPairs: "No pair with traffic — no stats to display (not zero).",

    // Main table headers
    thPeer: 'Peer',
    thIpNodeId: 'IP / Node ID',
    thDirection: 'Direction',
    thProto: 'Proto',
    thMode: 'Mode',
    thSens: 'Direction hint',
    thState: 'State',
    thSids: 'SIDs',
    thPushed: 'Pushed',
    thReceived: 'Received',
    thHits: 'Hits',
    thDropped: 'Dropped',
    thFlood: 'Flood',
    thCauses: 'Causes',
    thLastPush: 'Last push',
    thLastReceive: 'Last receive',

    // SIDs aria labels
    ariaCollapseSids: 'Collapse {count} SID(s) for {peer}',
    ariaExpandSids: 'Expand {count} SID(s) for {peer}',

    // Tooltip and footnotes
    titleFloodDrop: 'Flood drops (burst prevention)',
    queueFootnote: "Send queue maxSendQueue full → cause \"queue\". \"flood\" drops: peer burst prevention (dedicated column). \"quarantine\": peer temporarily excluded from routing (enforce mode). Active filter: effective gate of the peer.",

    // PeerQualitySection
    qualityHeading: 'Peer Quality (time window)',
    qualityFootnote: "Eligible population: accepted + invalid CWs (attributable content). Routing/protocol drops (loop, hop, queue, flood, filter) excluded. Hits = responses actually served by known origin (explicit \"unknown\", never confused with received/pushed volume).",
    loadingQuality: 'Loading quality…',
    noQualityData: 'No eligible data in window — no quality to display.',
    ariaQualityTable: 'Peer quality',

    // PeerQuality table headers
    thNode: 'Node',
    thAccepted: 'Accepted',
    thInvalid: 'Invalid',
    thEligibleTotal: 'Eligible total',
    thInvalidRatio: 'Invalid ratio',
    thQuarantined: 'Quarantined',

    // Hits table
    hitsHeading: 'Hits served by origin',
    loadingHits: 'Loading hits…',
    noHitsData: 'No hits served — no attribution to display.',
    ariaHitsTable: 'Hits served by origin',
    thOrigin: 'Origin',
    thLastHit: 'Last hit',

    // QuarantineSection
    quarantineHeading: 'Temporary Quarantine (opt-in)',
    quarantineFootnote: "Disabled by default. Observe mode (default): counts without excluding. Enforce: temporary exclusion from routing only — never a ban (spoofable UDP source). Bounded memory state (expiration + central admin release). Manual release applies only to central node; worker quarantine expires automatically in memory.",
    loadingQuarantine: 'Loading quarantine…',
    fieldEnabled: 'Enabled',
    fieldMode: 'Mode',
    optObserveNoExclusion: 'observe (no exclusion)',
    optEnforceRoutingExclusion: 'enforce (routing exclusion)',
    fieldMinSamples: 'Min samples',
    fieldInvalidRatioBps: 'Invalid ratio (bps /10000)',
    fieldDurationMs: 'Duration (ms)',
    saveVersion: 'Save (version {version})',
    toastQuarantineUpdated: 'Quarantine updated (relayed to workers)',
    toastReleaseCentralOnly: "Manual release applies only to central node; workers expire automatically.",
    toastQuarantineReleased: 'Central quarantine released: {id}',
    toastPeerNotFoundInQuarantine: 'Peer not found in central quarantine: {id}',

    activeEntriesHeading: 'Active entries ({count}) — observed: {observed}, excluded: {enforced}',
    noActiveQuarantine: 'No active quarantine.',
    ariaQuarantinesTable: 'Active quarantines',
    thReason: 'Reason',
    thExpires: 'Expires',
    thAction: 'Action',
    observedOnly: 'observed',
    enforcedExclusion: 'excluded',
    workerBadgeTitle: 'Worker quarantine expires automatically in memory',
    workerBadgeAria: 'Automatic worker expiration in memory',
    workerBadgeLabel: 'Auto-expire (worker)',
    releaseBtn: 'Release',
    fieldReleasePeerId: 'Release a peer (exact id)',
    releaseCentralBtn: 'Release (central)',

    // QuorumSection
    quorumHeading: 'Targeted CacheEx Quorum',
    quorumFootnote: "Off by default. Exact match of already-valid CWs for the same ECM identity (profile/CAID/provider/SID/exact parity/hash — without ECM body, push limitation). Minimum 2 verified distinct transport origins (no cryptographic proof). Enforce: no injection without quorum, never silent fallback.",
    loadingQuorum: 'Loading quorum…',
    toastInvalidCaid: 'Invalid CAID: {token}',
    toastQuorumUpdated: 'Quorum updated (relayed to workers)',
    optQuorumOff: 'off (disabled)',
    optQuorumObserve: 'observe (injects + counts)',
    optQuorumEnforce: 'enforce (quorum required)',
    fieldMinOrigins: 'Min origins',
    fieldProfiles: 'Profiles (CSV, empty = all)',
    fieldCaidsHex: 'Hex CAIDs (CSV, empty = all)',
    placeholderCaids: 'e.g.: 1830, 0500',
    countersHeading: 'Counters',
    countersSummary: 'keys: {keys} · observed: {observed} · quorum: {quorum} · pending: {pending} · mismatches: {mismatch} · expired: {expired}',
    trackedKeysHeading: 'Tracked keys ({count})',
    noTrackedKeys: 'No tracked keys in window.',
    ariaQuorumKeysTable: 'Tracked quorum keys',
    thQuorumProfile: 'Profile',
    thQuorumCaid: 'CAID',
    thQuorumSid: 'SID',
    thQuorumParity: 'Parity',
    thQuorumOrigins: 'Origins',
    thQuorumQuorum: 'Quorum',
    quorumReached: 'reached',
    quorumPending: 'pending',
  },
  {
    // Reasons
    reasonLoop: 'boucle',
    reasonHop: 'hop',
    reasonChecksum: 'checksum',
    reasonFiltered: 'filtre',
    reasonInvalid: 'invalide',
    reasonQueue: 'file',
    reasonFlood: 'flood',
    reasonQuarantine: 'quarantaine',
    reasonQuorum: 'quorum (en attente/divergent)',
    unknown: 'inconnu',

    // Direction hints
    dirOutMode2: 'nous recevons du serveur',
    dirOutMode3: 'nous émettons vers le serveur',
    dirInMode2: 'nous émettons vers le client',
    dirInMode3: 'nous recevons du client',

    // Directions
    dirOutbound: 'sortant',
    dirInbound: 'entrant',

    // State
    connected: 'connecté',

    // CacheExPage
    title: 'Stats CacheEx par pair',
    title404: 'Stats CacheEx',
    endpointUnavailable: "Endpoint indisponible sur ce backend (404) — fonctionnalité livrée par l'agent backend.",
    refresh: 'Actualiser',
    resetCounters: 'Réinitialiser compteurs',
    toastResetOk: 'Compteurs CacheEx réinitialisés',
    loading: 'Chargement des stats CacheEx…',
    summary: "Généré : {generated}. Modes asymétriques : mode 2 = réception des push, mode 3 = émission des push. Une paire sans trafic n'apparaît pas (jamais de zéros fabriqués).",
    noPairs: "Aucune paire avec trafic — pas de stats à afficher (ce n'est pas zéro).",

    // Main table headers
    thPeer: 'Pair',
    thIpNodeId: 'IP / Node ID',
    thDirection: 'Direction',
    thProto: 'Proto',
    thMode: 'Mode',
    thSens: 'Sens',
    thState: 'État',
    thSids: 'SIDs',
    thPushed: 'Poussés',
    thReceived: 'Reçus',
    thHits: 'Hits',
    thDropped: 'Jetés',
    thFlood: 'Flood',
    thCauses: 'Causes',
    thLastPush: 'Dernier push',
    thLastReceive: 'Dernière réception',

    // SIDs aria labels
    ariaCollapseSids: 'Replier {count} SID(s) pour {peer}',
    ariaExpandSids: 'Déplier {count} SID(s) pour {peer}',

    // Tooltip and footnotes
    titleFloodDrop: 'Rejets flood (anti-rafale)',
    queueFootnote: "File d'émission maxSendQueue pleine → cause « file » (queue). Rejets « flood » : anti-rafale pair (colonne dédiée). « quarantaine » : pair exclu temporairement du routage (mode enforce). Filtre actif : gate effective du pair.",

    // PeerQualitySection
    qualityHeading: 'Qualité des pairs (fenêtre temporelle)',
    qualityFootnote: "Population éligible : CW acceptées + invalides (contenu imputable). Drops de routage/protocole (boucle, hop, file, flood, filtre) exclus. Hits = réponses réellement servies par origine connue (« unknown » explicite, jamais confondu avec le volume reçu/poussé).",
    loadingQuality: 'Chargement qualité…',
    noQualityData: 'Aucune donnée éligible dans la fenêtre — pas de qualité à afficher.',
    ariaQualityTable: 'Qualité des pairs',

    // PeerQuality table headers
    thNode: 'Nœud',
    thAccepted: 'Acceptées',
    thInvalid: 'Invalides',
    thEligibleTotal: 'Total éligible',
    thInvalidRatio: 'Ratio invalide',
    thQuarantined: 'Quarantiné',

    // Hits table
    hitsHeading: 'Hits servis par origine',
    loadingHits: 'Chargement hits…',
    noHitsData: "Aucun hit servi — pas d'attribution à afficher.",
    ariaHitsTable: 'Hits servis par origine',
    thOrigin: 'Origine',
    thLastHit: 'Dernier hit',

    // QuarantineSection
    quarantineHeading: 'Quarantaine temporaire (opt-in)',
    quarantineFootnote: "Désactivée par défaut. Mode observe (défaut) : comptabilise sans exclure. Enforce : exclusion temporaire du routage uniquement — jamais un bannissement (source UDP usurpable). État mémoire borné (expiration + levée admin centrale). Levée manuelle applicable uniquement au nœud central ; la quarantaine des workers expire automatiquement en mémoire.",
    loadingQuarantine: 'Chargement quarantaine…',
    fieldEnabled: 'Activée',
    fieldMode: 'Mode',
    optObserveNoExclusion: 'observe (sans exclusion)',
    optEnforceRoutingExclusion: 'enforce (exclusion routage)',
    fieldMinSamples: 'Échantillons min',
    fieldInvalidRatioBps: 'Ratio invalide (bps /10000)',
    fieldDurationMs: 'Durée (ms)',
    saveVersion: 'Enregistrer (version {version})',
    toastQuarantineUpdated: 'Quarantaine mise à jour (relayée aux workers)',
    toastReleaseCentralOnly: "La levée manuelle s'applique uniquement au nœud central ; les workers expirent automatiquement.",
    toastQuarantineReleased: 'Quarantaine centrale levée : {id}',
    toastPeerNotFoundInQuarantine: 'Pair non trouvé dans la quarantaine centrale : {id}',

    activeEntriesHeading: 'Entrées actives ({count}) — observées : {observed}, exclues : {enforced}',
    noActiveQuarantine: 'Aucune quarantaine active.',
    ariaQuarantinesTable: 'Quarantaines actives',
    thReason: 'Raison',
    thExpires: 'Expire',
    thAction: 'Action',
    observedOnly: 'observée',
    enforcedExclusion: 'exclue',
    workerBadgeTitle: 'La quarantaine des workers expire automatiquement en mémoire',
    workerBadgeAria: 'Expiration worker automatique en mémoire',
    workerBadgeLabel: 'Auto-expire (worker)',
    releaseBtn: 'Lever',
    fieldReleasePeerId: 'Lever un pair (id exact)',
    releaseCentralBtn: 'Lever (centrale)',

    // QuorumSection
    quorumHeading: 'Quorum CacheEx ciblé',
    quorumFootnote: "Off par défaut. Comparaison exacte de CW déjà valides pour une même identité ECM (profil/CAID/provider/SID/parité exacte/hash — sans corps ECM, limite des push). Minimum 2 origines de transport vérifiées distinctes (pas de preuve cryptographique). Enforce : pas d'injection sans quorum, jamais de repli silencieux.",
    loadingQuorum: 'Chargement quorum…',
    toastInvalidCaid: 'CAID invalide : {token}',
    toastQuorumUpdated: 'Quorum mis à jour (relayé aux workers)',
    optQuorumOff: 'off (désactivé)',
    optQuorumObserve: 'observe (injecte + comptabilise)',
    optQuorumEnforce: 'enforce (quorum exigé)',
    fieldMinOrigins: 'Origines min',
    fieldProfiles: 'Profils (CSV, vide = tous)',
    fieldCaidsHex: 'CAIDs hex (CSV, vide = tous)',
    placeholderCaids: 'ex : 1830, 0500',
    countersHeading: 'Compteurs',
    countersSummary: 'clés : {keys} · observées : {observed} · quorum : {quorum} · en attente : {pending} · divergences : {mismatch} · expirées : {expired}',
    trackedKeysHeading: 'Clés suivies ({count})',
    noTrackedKeys: 'Aucune clé suivie dans la fenêtre.',
    ariaQuorumKeysTable: 'Clés quorum suivies',
    thQuorumProfile: 'Profil',
    thQuorumCaid: 'CAID',
    thQuorumSid: 'SID',
    thQuorumParity: 'Parité',
    thQuorumOrigins: 'Origines',
    thQuorumQuorum: 'Quorum',
    quorumReached: 'atteint',
    quorumPending: 'en attente',
  },
);
