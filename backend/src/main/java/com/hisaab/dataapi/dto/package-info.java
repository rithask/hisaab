/**
 * The Data API's requests and replies ({@code /api/v1}). They're a contract with other apps, separate from the
 * screens' DTOs: snake_case names, every key always present ({@code null} when empty), ids as strings, money as
 * {@code {value, currency}} with the value as a decimal string, times with their offset. Changing a shape here
 * is a major release (docs/versioning.md).
 */
package com.hisaab.dataapi.dto;
