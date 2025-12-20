package com.api.service;

import com.api.dto.WorkflowScheduleRequestDTO;
import com.api.dto.WorkflowScheduleResponseDTO;
import com.api.entity.WorkflowSchedule;
import com.api.exception.InvalidCronExpressionException;
import com.api.exception.InvalidScheduleRequestException;
import com.api.repository.WorkflowScheduleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduleServiceTest {

    @Mock
    private WorkflowScheduleRepository workflowScheduleRepository;

    @InjectMocks
    private ScheduleService scheduleService;

    @Test
    void createSchedule_ValidRequest_ShouldSaveAndReturnResponse() {
        WorkflowScheduleRequestDTO request = new WorkflowScheduleRequestDTO();
        request.setName("Daily Backup");
        request.setCronExpression("0 0 * * *");
        request.setDescription("Runs at midnight");
        request.setTimezone("Asia/Ho_Chi_Minh");
        request.setWorkflowDefinition("name: test\ntasks:\n  - id: task1\n    command: echo hello");

        WorkflowSchedule savedSchedule = new WorkflowSchedule();
        savedSchedule.setId("schedule-123");
        savedSchedule.setName(request.getName());
        savedSchedule.setCronExpression(request.getCronExpression());
        savedSchedule.setDescription(request.getDescription());
        savedSchedule.setTimezone(request.getTimezone());
        savedSchedule.setStatus(WorkflowSchedule.ScheduleStatus.ACTIVE);
        savedSchedule.setNextRunAt(LocalDateTime.now().plusDays(1));
        savedSchedule.setCreatedAt(LocalDateTime.now());

        when(workflowScheduleRepository.save(any(WorkflowSchedule.class))).thenReturn(savedSchedule);

        WorkflowScheduleResponseDTO response = scheduleService.createSchedule(request);

        assertNotNull(response);
        assertEquals("schedule-123", response.getId());
        assertEquals("Daily Backup", response.getName());
        assertEquals("ACTIVE", response.getStatus());

        ArgumentCaptor<WorkflowSchedule> captor = ArgumentCaptor.forClass(WorkflowSchedule.class);
        verify(workflowScheduleRepository).save(captor.capture());

        WorkflowSchedule capturedSchedule = captor.getValue();
        assertEquals("Asia/Ho_Chi_Minh", capturedSchedule.getTimezone());
        assertEquals(WorkflowSchedule.ScheduleStatus.ACTIVE, capturedSchedule.getStatus());
        assertNotNull(capturedSchedule.getNextRunAt(), "Next run time should be calculated");
        assertEquals("0 0 * * *", capturedSchedule.getCronExpression());
    }

    @Test
    void createSchedule_NullName_ShouldThrowException() {
        WorkflowScheduleRequestDTO request = new WorkflowScheduleRequestDTO();
        request.setName(null);
        request.setCronExpression("0 0 * * *");
        request.setWorkflowDefinition("name: test\ntasks:\n  - id: task1\n    command: echo hello");

        InvalidScheduleRequestException exception = assertThrows(
                InvalidScheduleRequestException.class,
                () -> scheduleService.createSchedule(request)
        );

        assertEquals("Schedule name is required", exception.getMessage());
    }

    @Test
    void createSchedule_BlankName_ShouldThrowException() {
        WorkflowScheduleRequestDTO request = new WorkflowScheduleRequestDTO();
        request.setName("   ");
        request.setCronExpression("0 0 * * *");
        request.setWorkflowDefinition("name: test\ntasks:\n  - id: task1\n    command: echo hello");

        InvalidScheduleRequestException exception = assertThrows(
                InvalidScheduleRequestException.class,
                () -> scheduleService.createSchedule(request)
        );

        assertEquals("Schedule name is required", exception.getMessage());
    }

    @Test
    void createSchedule_NullCronExpression_ShouldThrowException() {
        WorkflowScheduleRequestDTO request = new WorkflowScheduleRequestDTO();
        request.setName("Test Schedule");
        request.setCronExpression(null);
        request.setWorkflowDefinition("name: test\ntasks:\n  - id: task1\n    command: echo hello");

        InvalidScheduleRequestException exception = assertThrows(
                InvalidScheduleRequestException.class,
                () -> scheduleService.createSchedule(request)
        );

        assertEquals("Cron expression is required", exception.getMessage());
    }

    @Test
    void createSchedule_InvalidCronExpression_ShouldThrowException() {
        WorkflowScheduleRequestDTO request = new WorkflowScheduleRequestDTO();
        request.setName("Test Schedule");
        request.setCronExpression("invalid cron");
        request.setWorkflowDefinition("name: test\ntasks:\n  - id: task1\n    command: echo hello");

        InvalidCronExpressionException exception = assertThrows(
                InvalidCronExpressionException.class,
                () -> scheduleService.createSchedule(request)
        );

        assertTrue(exception.getMessage().contains("Invalid cron expression"));
    }

    @Test
    void createSchedule_InvalidTimezone_ShouldThrowException() {
        WorkflowScheduleRequestDTO request = new WorkflowScheduleRequestDTO();
        request.setName("Test Schedule");
        request.setCronExpression("0 0 * * *");
        request.setTimezone("Invalid/Timezone");
        request.setWorkflowDefinition("name: test\ntasks:\n  - id: task1\n    command: echo hello");

        InvalidScheduleRequestException exception = assertThrows(
                InvalidScheduleRequestException.class,
                () -> scheduleService.createSchedule(request)
        );

        assertTrue(exception.getMessage().contains("Invalid timezone"));
    }

    @Test
    void createSchedule_NullWorkflowDefinition_ShouldThrowException() {
        WorkflowScheduleRequestDTO request = new WorkflowScheduleRequestDTO();
        request.setName("Test Schedule");
        request.setCronExpression("0 0 * * *");
        request.setWorkflowDefinition(null);

        InvalidScheduleRequestException exception = assertThrows(
                InvalidScheduleRequestException.class,
                () -> scheduleService.createSchedule(request)
        );

        assertEquals("Workflow definition is required", exception.getMessage());
    }

    @Test
    void createSchedule_DefaultTimezone_ShouldUseUTC() {
        WorkflowScheduleRequestDTO request = new WorkflowScheduleRequestDTO();
        request.setName("Test Schedule");
        request.setCronExpression("0 0 * * *");
        request.setTimezone(null);
        request.setWorkflowDefinition("name: test\ntasks:\n  - id: task1\n    command: echo hello");

        WorkflowSchedule savedSchedule = new WorkflowSchedule();
        savedSchedule.setId("schedule-456");
        savedSchedule.setName(request.getName());
        savedSchedule.setCronExpression(request.getCronExpression());
        savedSchedule.setTimezone("UTC");
        savedSchedule.setStatus(WorkflowSchedule.ScheduleStatus.ACTIVE);
        savedSchedule.setNextRunAt(LocalDateTime.now().plusDays(1));
        savedSchedule.setCreatedAt(LocalDateTime.now());

        when(workflowScheduleRepository.save(any(WorkflowSchedule.class))).thenReturn(savedSchedule);

        WorkflowScheduleResponseDTO response = scheduleService.createSchedule(request);

        assertEquals("UTC", response.getTimezone());

        ArgumentCaptor<WorkflowSchedule> captor = ArgumentCaptor.forClass(WorkflowSchedule.class);
        verify(workflowScheduleRepository).save(captor.capture());
        assertEquals("UTC", captor.getValue().getTimezone());
    }

    @Test
    void createSchedule_SixFieldCronExpression_ShouldWork() {
        WorkflowScheduleRequestDTO request = new WorkflowScheduleRequestDTO();
        request.setName("Every Minute");
        request.setCronExpression("0 * * * * *");
        request.setWorkflowDefinition("name: test\ntasks:\n  - id: task1\n    command: echo hello");

        WorkflowSchedule savedSchedule = new WorkflowSchedule();
        savedSchedule.setId("schedule-789");
        savedSchedule.setName(request.getName());
        savedSchedule.setCronExpression(request.getCronExpression());
        savedSchedule.setTimezone("UTC");
        savedSchedule.setStatus(WorkflowSchedule.ScheduleStatus.ACTIVE);
        savedSchedule.setNextRunAt(LocalDateTime.now().plusMinutes(1));
        savedSchedule.setCreatedAt(LocalDateTime.now());

        when(workflowScheduleRepository.save(any(WorkflowSchedule.class))).thenReturn(savedSchedule);

        WorkflowScheduleResponseDTO response = scheduleService.createSchedule(request);

        assertNotNull(response);
        assertEquals("schedule-789", response.getId());
    }

    @Test
    void createSchedule_FiveFieldCronExpression_ShouldConvertToSixField() {
        WorkflowScheduleRequestDTO request = new WorkflowScheduleRequestDTO();
        request.setName("Daily Job");
        request.setCronExpression("30 2 * * *");
        request.setTimezone("UTC");
        request.setWorkflowDefinition("name: test\ntasks:\n  - id: task1\n    command: echo hello");

        WorkflowSchedule savedSchedule = new WorkflowSchedule();
        savedSchedule.setId("schedule-abc");
        savedSchedule.setName(request.getName());
        savedSchedule.setCronExpression(request.getCronExpression());
        savedSchedule.setTimezone("UTC");
        savedSchedule.setStatus(WorkflowSchedule.ScheduleStatus.ACTIVE);
        savedSchedule.setNextRunAt(LocalDateTime.now().plusDays(1));
        savedSchedule.setCreatedAt(LocalDateTime.now());

        when(workflowScheduleRepository.save(any(WorkflowSchedule.class))).thenReturn(savedSchedule);

        WorkflowScheduleResponseDTO response = scheduleService.createSchedule(request);

        assertNotNull(response);
        assertNotNull(response.getNextRunAt());
    }
}
