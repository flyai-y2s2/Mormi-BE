package com.mormi.backend.report;

import static com.mormi.backend.report.LocalReportAdminDtos.LocalLearnerResult;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mormi.backend.common.ApiException;
import com.mormi.backend.learner.Learner;
import com.mormi.backend.learner.LearnerRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

class LocalReportAdminServiceTest {

    private LearnerRepository learnerRepository;
    private LocalReportAdminService service;

    @BeforeEach
    void setUp() {
        learnerRepository = mock(LearnerRepository.class);
        service = new LocalReportAdminService(learnerRepository);
    }

    @Test
    void trimsTheQueryCapsTheLimitAndReturnsOnlyIdAndName() {
        when(learnerRepository.findByDisplayNameContainingIgnoreCaseOrderByDisplayNameAscIdAsc(
                        eq("이재"), any(Pageable.class)))
                .thenReturn(List.of(learner(19L, "이재용")));

        assertThat(service.search("  이재  ", 99))
                .containsExactly(new LocalLearnerResult(19L, "이재용"));
        verify(learnerRepository).findByDisplayNameContainingIgnoreCaseOrderByDisplayNameAscIdAsc(
                eq("이재"), org.mockito.ArgumentMatchers.argThat(page -> page.getPageSize() == 10));
    }

    @Test
    void rejectsQueriesShorterThanTwoCharacters() {
        assertThatThrownBy(() -> service.search("이", 10)).isInstanceOf(ApiException.class);

        verifyNoInteractions(learnerRepository);
    }

    private Learner learner(long id, String displayName) {
        Learner learner = Learner.register(displayName, "R-" + id, id);
        ReflectionTestUtils.setField(learner, "id", id);
        return learner;
    }
}
