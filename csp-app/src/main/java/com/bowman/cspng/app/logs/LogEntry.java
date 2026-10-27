package com.bowman.cspng.app.logs;

/**
 * Entrée de log structurée avec séquence monotone.
 *
 * @param seq       numéro de séquence strictement croissant
 * @param timestamp epoch millisecondes
 * @param level     niveau normalisé (DEBUG, INFO, WARN, ERROR)
 * @param logger    nom du logger (abrégé)
 * @param message   texte du message formaté
 */
public record LogEntry(
    long seq,
    long timestamp,
    String level,
    String logger,
    String message
) {}
