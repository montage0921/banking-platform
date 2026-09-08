package com.group1.banking.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Export cache entity for idempotent PDF export. (T014)
 * Table: export_cache — purged after 72 hours.
 */
@Entity
@Table(name = "export_cache",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_ec_account_param_hash",
                columnNames = {"account_id", "param_hash"}))
public class ExportCacheEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "cache_id")
    private Long cacheId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "param_hash", nullable = false, length = 64)
    private String paramHash;

    @Lob
    @Column(name = "pdf_data", nullable = false)
    private byte[] pdfData;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Long getCacheId() { return cacheId; }
    public void setCacheId(Long cacheId) { this.cacheId = cacheId; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }
    public String getParamHash() { return paramHash; }
    public void setParamHash(String paramHash) { this.paramHash = paramHash; }
    public byte[] getPdfData() { return pdfData; }
    public void setPdfData(byte[] pdfData) { this.pdfData = pdfData; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
