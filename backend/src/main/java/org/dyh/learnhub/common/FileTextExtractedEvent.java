package org.dyh.learnhub.common;

/** Only new uploads and explicitly requested extraction publish this event; startup backfills do not. */
public record FileTextExtractedEvent(Long fileId, String title, String text) { }
