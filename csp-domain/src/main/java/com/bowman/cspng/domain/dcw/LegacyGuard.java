package com.bowman.cspng.domain.dcw;

/**
 * Garde historique du point d'appel, utilisée quand le mode checksum effectif est INHERIT
 * (aucune règle scopée explicite ni défaut de policy explicite) ou quand la policy est désactivée.
 *
 * <ul>
 *   <li>{@link #PERMISSIVE} : comportement historique du StrictEcmRouter (aucun contrôle checksum).</li>
 *   <li>{@link #CHECKSUM_REQUIRED} : comportement historique manager / UDP / CSPv2 (checksum DVB exigé).</li>
 * </ul>
 */
public enum LegacyGuard {
  PERMISSIVE,
  CHECKSUM_REQUIRED
}
