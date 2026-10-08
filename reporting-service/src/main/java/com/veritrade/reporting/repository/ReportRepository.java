package com.veritrade.reporting.repository;

import com.veritrade.reporting.domain.Report;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportRepository extends JpaRepository<Report, UUID> {
}
