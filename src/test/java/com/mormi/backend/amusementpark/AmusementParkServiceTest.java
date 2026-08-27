package com.mormi.backend.amusementpark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mormi.backend.progress.ThemeProgressService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AmusementParkServiceTest {

    @Test
    void completedVisitIsReusedAsAnIndependentPracticeVisit() {
        AmusementParkVisitRepository visitRepository = mock(AmusementParkVisitRepository.class);
        AmusementParkVisitStageRepository stageRepository =
                mock(AmusementParkVisitStageRepository.class);
        ThemeProgressService progressService = mock(ThemeProgressService.class);
        AmusementParkService service =
                new AmusementParkService(visitRepository, stageRepository, progressService);

        AmusementParkVisit completed = AmusementParkVisit.start(7L);
        ReflectionTestUtils.setField(completed, "id", 31L);
        completed.advanceTo(AmusementParkStage.COMPLETE);

        when(progressService.syncAmusementParkUnlock(7L)).thenReturn(true);
        when(visitRepository.findFirstByLearnerIdAndCompletedAtIsNullOrderByIdDesc(7L))
                .thenReturn(Optional.empty());
        when(visitRepository.findFirstByLearnerIdOrderByIdDesc(7L))
                .thenReturn(Optional.of(completed));
        when(visitRepository.findByPublicId(completed.getPublicId()))
                .thenReturn(Optional.of(completed));
        when(stageRepository.findByParkVisitIdOrderByIdAsc(31L)).thenReturn(List.of());

        var result = service.start(7L);

        assertThat(result.visitId()).isEqualTo(completed.getPublicId());
        assertThat(result.completedAt()).isNotNull();
        assertThat(result.stageProgress()).containsOnly(
                org.assertj.core.api.Assertions.entry("ticket", "completed"),
                org.assertj.core.api.Assertions.entry("snack_split", "completed"),
                org.assertj.core.api.Assertions.entry("pass_break_even", "completed"));
        verify(visitRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
