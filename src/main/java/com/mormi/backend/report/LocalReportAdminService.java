package com.mormi.backend.report;

import static com.mormi.backend.report.LocalReportAdminDtos.LocalLearnerResult;

import com.mormi.backend.common.ApiException;
import com.mormi.backend.learner.LearnerRepository;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class LocalReportAdminService {

    private final LearnerRepository learnerRepository;

    public LocalReportAdminService(LearnerRepository learnerRepository) {
        this.learnerRepository = learnerRepository;
    }

    public List<LocalLearnerResult> search(String rawQuery, int rawLimit) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.length() < 2) {
            throw ApiException.badRequest("query", "이름을 두 글자 이상 입력해 주세요.");
        }
        int limit = Math.max(1, Math.min(rawLimit, 10));
        return learnerRepository
                .findByDisplayNameContainingIgnoreCaseOrderByDisplayNameAscIdAsc(
                        query, PageRequest.of(0, limit))
                .stream()
                .map(learner -> new LocalLearnerResult(learner.getId(), learner.getDisplayName()))
                .toList();
    }
}
