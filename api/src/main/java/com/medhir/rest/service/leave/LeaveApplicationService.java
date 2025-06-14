package com.medhir.rest.service.leave;

import com.medhir.rest.service.EmployeeService;
import com.medhir.rest.dto.EmployeeWithLeaveDetailsDTO;
import com.medhir.rest.dto.ManagerEmployeeDTO;
import com.medhir.rest.exception.ResourceNotFoundException;
import com.medhir.rest.dto.leave.LeaveWithEmployeeDetails;
import com.medhir.rest.dto.leave.UpdateLeaveStatusRequest;
import com.medhir.rest.model.leave.LeaveBalance;
import com.medhir.rest.model.leave.LeaveModel;
import com.medhir.rest.repository.leave.LeaveBalanceRepository;
import com.medhir.rest.repository.leave.LeaveRepository;
import com.medhir.rest.service.CompanyService;
import com.medhir.rest.service.settings.DepartmentService;
import com.medhir.rest.service.settings.LeaveTypeService;
import com.medhir.rest.service.settings.LeavePolicyService;
import com.medhir.rest.utils.GeneratedId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class LeaveApplicationService {

    @Autowired
    private LeaveRepository leaveRepository;

    @Autowired
    private EmployeeService employeeService;

    @Autowired
    private GeneratedId generatedId;

    @Autowired
    private LeaveBalanceService leaveBalanceService;

    @Autowired
    private DepartmentService departmentService;

    @Autowired
    private LeavePolicyService leavePolicyService;

    @Autowired
    private LeaveTypeService leaveTypeService;

    @Autowired
    private CompanyService companyService;

    @Autowired
    private LeaveBalanceRepository leaveBalanceRepository;

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${attendance.service.url}")
    private String ATTENDANCE_SERVICE_URL;

    public LeaveModel applyLeave(LeaveModel request) {
        // Validate employee exists and get their details
        Optional<EmployeeWithLeaveDetailsDTO> employeeOpt = employeeService.getEmployeeById(request.getEmployeeId());
        if (employeeOpt.isEmpty()) {
            throw new ResourceNotFoundException("Employee not found with ID: " + request.getEmployeeId());
        }

        // Validate company exists
        companyService.getCompanyById(request.getCompanyId())
                .orElseThrow(() -> new ResourceNotFoundException("Company not found with ID: " + request.getCompanyId()));

        // Set end date equal to start date if not provided
        if (request.getEndDate() == null) {
            request.setEndDate(request.getStartDate());
        }

        // Create leave record
        LeaveModel leave = new LeaveModel();
        leave.setLeaveId(generatedId.generateId("LID", LeaveModel.class, "leaveId"));
        leave.setEmployeeId(request.getEmployeeId());
        leave.setCompanyId(request.getCompanyId()); // Set companyId from request
        leave.setLeaveName(request.getLeaveName());
        leave.setLeaveType(request.getLeaveType());
        leave.setStartDate(request.getStartDate());
        leave.setEndDate(request.getEndDate());
        leave.setShiftType(request.getShiftType()); // Store as string
        leave.setReason(request.getReason());
        leave.setStatus("Pending");

        // Check balance and add warning if insufficient (only for regular leaves)
        if ("Leave".equals(request.getLeaveName())) {
            checkAndAddWarning(leave);
        }

        return leaveRepository.save(leave);
    }

    private void checkAndAddWarning(LeaveModel leave) {
        double requestedDays = calculateLeaveDays(leave);
        LeaveBalance currentBalance = leaveBalanceService.getCurrentMonthBalance(leave.getEmployeeId());

        // First check comp-off balance
        double compOffBalance = currentBalance.getRemainingCompOffLeaves();
        double annualBalance = currentBalance.getRemainingAnnualLeaves();
        double totalAvailable = compOffBalance + annualBalance;

        if (requestedDays > totalAvailable) {
            leave.setRemarks("WARNING: Insufficient leave balance. This will be marked as LOP if approved. " +
                    String.format("Requested: %.1f days, Available: %.1f days (Comp-off: %.1f, Annual: %.1f)",
                            requestedDays, totalAvailable, compOffBalance, annualBalance));
        }
    }

    public LeaveModel updateLeaveStatus(UpdateLeaveStatusRequest request) {
        Optional<LeaveModel> leaveOpt = leaveRepository.findByLeaveId(request.getLeaveId());
        if (leaveOpt.isEmpty()) {
            throw new ResourceNotFoundException("Leave not found with ID: " + request.getLeaveId());
        }

        LeaveModel leave = leaveOpt.get();

        // Check if leave is already approved or rejected
        if ("Approved".equals(leave.getStatus()) || "Rejected".equals(leave.getStatus())) {
            throw new IllegalArgumentException("Leave is already " + leave.getStatus().toLowerCase());
        }

        if (!"Approved".equals(request.getStatus()) && !"Rejected".equals(request.getStatus())) {
            throw new IllegalArgumentException("Status must be either 'Approved' or 'Rejected'");
        }

        leave.setStatus(request.getStatus());
        leave.setRemarks(request.getRemarks());

        if ("Approved".equals(request.getStatus())) {
            if ("Leave".equals(leave.getLeaveName())) {
                handleRegularLeaveApproval(leave);
            } else if ("Comp-Off".equals(leave.getLeaveName())) {
                // For comp-off, use the new balance update method
                double days = calculateLeaveDays(leave);
                updateCompOffBalance(leave.getEmployeeId(), days);
            }
        }

        return leaveRepository.save(leave);
    }

    private void handleRegularLeaveApproval(LeaveModel leave) {
        double requestedDays = calculateLeaveDays(leave);
        LeaveBalance currentBalance = leaveBalanceService.getCurrentMonthBalance(leave.getEmployeeId());

        double compOffBalance = currentBalance.getRemainingCompOffLeaves();
        double annualBalance = currentBalance.getRemainingAnnualLeaves();
        double totalAvailable = compOffBalance + annualBalance;

        // First use comp-off balance if available
        if (compOffBalance > 0) {
            leaveBalanceService.updateCompOffLeavesTaken(leave.getEmployeeId(), compOffBalance);
            requestedDays -= compOffBalance;
        }

        // Then use annual leave balance (can go negative)
        if (requestedDays > 0) {
            leaveBalanceService.updateLeavesTaken(leave.getEmployeeId(), requestedDays);
        }

        // Calculate how many days we can mark as present with approved leave
        double daysWithLeave = Math.min(totalAvailable, requestedDays + compOffBalance);
        double daysAsLOP = Math.max(0, requestedDays - totalAvailable);

        if (daysWithLeave > 0) {
            // Calculate dates for days with leave
            LocalDate leaveStartDate = leave.getStartDate();
            LocalDate leaveEndDate = leaveStartDate.plusDays((long)daysWithLeave - 1);

            // Mark days with available leave as present with approved leave
            markPresentWithApprovedLeaveInAttendance(
                    leave.getEmployeeId(),
                    leaveStartDate,
                    leaveEndDate,
                    leave.getReason(),
                    leave.getLeaveId()
            );

            // If there are days to mark as LOP
            if (daysAsLOP > 0) {
                LocalDate lopStartDate = leaveEndDate.plusDays(1);
                markApprovedLOPInAttendance(
                        leave.getEmployeeId(),
                        lopStartDate,
                        leave.getEndDate(),
                        leave.getReason(),
                        leave.getLeaveId()
                );
            }
        } else {
            // All days are LOP
            markApprovedLOPInAttendance(
                    leave.getEmployeeId(),
                    leave.getStartDate(),
                    leave.getEndDate(),
                    leave.getReason(),
                    leave.getLeaveId()
            );
        }
    }

    private void updateCompOffBalance(String employeeId, double compOffDays) {
        LeaveBalance currentBalance = leaveBalanceService.getCurrentMonthBalance(employeeId);
        
        // Get initial values
        double remainingAnnualLeaves = currentBalance.getRemainingAnnualLeaves();
        double remainingCompOffLeaves = currentBalance.getRemainingCompOffLeaves();
        
        // Update comp-off earned fields
        currentBalance.setCompOffLeavesEarned(compOffDays);
        currentBalance.setTotalCompOffLeavesEarnedSinceJanuary(
            currentBalance.getTotalCompOffLeavesEarnedSinceJanuary() + compOffDays
        );
        
        // Add new comp-off days
        double totalCompOffAvailable = remainingCompOffLeaves + compOffDays;
        
        // If there's a negative leave balance, use comp-off to compensate
        if (remainingAnnualLeaves < 0) {
            double negativeBalance = Math.abs(remainingAnnualLeaves);
            double compOffUsed = Math.min(negativeBalance, totalCompOffAvailable);
            
            // Update remaining comp-off (only what's left after compensation)
            remainingCompOffLeaves = totalCompOffAvailable - compOffUsed;
        } else {
            // If no negative balance, just add the new comp-off
            remainingCompOffLeaves = totalCompOffAvailable;
        }
        
        // Calculate new leave balance as sum of remaining annual leaves and comp-off earned
        double newLeaveBalance = remainingAnnualLeaves + compOffDays;
        
        // Update the balances
        currentBalance.setRemainingCompOffLeaves(remainingCompOffLeaves);
        currentBalance.setNewLeaveBalance(newLeaveBalance);
        
        // Save the updated balance
        leaveBalanceRepository.save(currentBalance);
    }

    private double calculateLeaveDays(LeaveModel leave) {
        long totalDays = ChronoUnit.DAYS.between(leave.getStartDate(), leave.getEndDate()) + 1;

        String shiftType = leave.getShiftType();
        if ("FULL_DAY".equals(shiftType)) {
            return totalDays;
        } else if ("FIRST_HALF".equals(shiftType) || "SECOND_HALF".equals(shiftType)) {
            return totalDays * 0.5;
        } else {
            return totalDays;
        }
    }

    public LeaveModel getLeaveByLeaveId(String leaveId) {
        return leaveRepository.findByLeaveId(leaveId)
                .orElseThrow(() -> new ResourceNotFoundException("Leave not found with ID: " + leaveId));
    }

    public List<LeaveWithEmployeeDetails> getLeavesByStatus(String companyId, String status) {
        // Validate company exists
        companyService.getCompanyById(companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Company not found with id: " + companyId));

        if (!"Pending".equals(status) && !"Approved".equals(status) && !"Rejected".equals(status)) {
            throw new IllegalArgumentException("Status must be either 'Pending', 'Approved', or 'Rejected'");
        }

        List<LeaveModel> leaves = leaveRepository.findByCompanyIdAndStatus(companyId, status);

        return leaves.stream().map(leave -> {
            LeaveWithEmployeeDetails leaveWithDetails = new LeaveWithEmployeeDetails();

            // Copy all fields from LeaveModel to LeaveWithEmployeeDetails
            leaveWithDetails.setId(leave.getId());
            leaveWithDetails.setLeaveId(leave.getLeaveId());
            leaveWithDetails.setEmployeeId(leave.getEmployeeId());
            leaveWithDetails.setCompanyId(leave.getCompanyId());
            leaveWithDetails.setLeaveName(leave.getLeaveName());
            leaveWithDetails.setLeaveType(leave.getLeaveType());
            leaveWithDetails.setStartDate(leave.getStartDate());
            leaveWithDetails.setEndDate(leave.getEndDate());
            leaveWithDetails.setShiftType(leave.getShiftType());
            leaveWithDetails.setReason(leave.getReason());
            leaveWithDetails.setStatus(leave.getStatus());
            leaveWithDetails.setRemarks(leave.getRemarks());
            leaveWithDetails.setCreatedAt(leave.getCreatedAt());

            // Get employee details
            Optional<EmployeeWithLeaveDetailsDTO> employeeOpt = employeeService.getEmployeeById(leave.getEmployeeId());
            if (employeeOpt.isPresent()) {
                EmployeeWithLeaveDetailsDTO employee = employeeOpt.get();
                leaveWithDetails.setEmployeeName(employee.getName());

                // Get department name
                try {
                    if (employee.getDepartment() != null && !employee.getDepartment().isEmpty()) {
                        leaveWithDetails.setDepartment(departmentService.getDepartmentById(employee.getDepartment()).getName());
                    }
                } catch (Exception e) {
                    leaveWithDetails.setDepartment(employee.getDepartment());
                }
            }

            return leaveWithDetails;
        }).collect(Collectors.toList());
    }

    private String markPresentWithApprovedLeaveInAttendance(String employeeId, LocalDate leaveDate, LocalDate endDate, String reason, String leaveId) {
        try {
            // Create list of dates between start and end date
            List<LocalDate> dates = leaveDate.datesUntil(endDate.plusDays(1)).collect(Collectors.toList());

            // Convert dates to string array
            String datesJson = dates.stream()
                    .map(date -> "\"" + date + "\"")
                    .collect(Collectors.joining(",", "[", "]"));

            String url = ATTENDANCE_SERVICE_URL + "/mark-bulk";

            // Create request body
            String requestBody = String.format(
                    "{\"employeeId\":\"%s\",\"status\":\"Leave\",\"dates\":%s,\"leaveId\":\"%s\"}",
                    employeeId,
                    datesJson,
                    leaveId
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> request = new HttpEntity<>(requestBody, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            return response.getBody();
        } catch (Exception e) {
            return "Error while applying leave: " + e.getMessage();
        }
    }

    private String markApprovedLOPInAttendance(String employeeId, LocalDate leaveDate, LocalDate endDate, String reason, String leaveId) {
        try {
            // Create list of dates between start and end date
            List<LocalDate> dates = leaveDate.datesUntil(endDate.plusDays(1)).collect(Collectors.toList());

            // Convert dates to string array
            String datesJson = dates.stream()
                    .map(date -> "\"" + date + "\"")
                    .collect(Collectors.joining(",", "[", "]"));

            String url = ATTENDANCE_SERVICE_URL + "/mark-bulk";

            // Create request body
            String requestBody = String.format(
                    "{\"employeeId\":\"%s\",\"status\":\"LOP\",\"dates\":%s,\"leaveId\":\"%s\"}",
                    employeeId,
                    datesJson,
                    leaveId
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> request = new HttpEntity<>(requestBody, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            return response.getBody();
        } catch (Exception e) {
            return "Error while applying leave: " + e.getMessage();
        }
    }

    public List<LeaveModel> getLeavesByEmployeeId(String employeeId) {
        List<LeaveModel> leaves = leaveRepository.findByEmployeeId(employeeId);
        return leaves;
    }

    public List<LeaveWithEmployeeDetails> getLeavesByManagerIdAndStatus(String managerId, String status) {
        if (!"Pending".equals(status) && !"Approved".equals(status) && !"Rejected".equals(status)) {
            throw new IllegalArgumentException("Status must be either 'Pending', 'Approved', or 'Rejected'");
        }

        // Get all employees reporting to this manager
        List<ManagerEmployeeDTO> teamMembers = employeeService.getEmployeesByManager(managerId);
        List<String> teamMemberIds = teamMembers.stream()
                .map(ManagerEmployeeDTO::getEmployeeId)
                .collect(Collectors.toList());

        // Get all leaves for team members with the specified status
        List<LeaveModel> leaves = leaveRepository.findByEmployeeIdInAndStatus(teamMemberIds, status);

        return leaves.stream().map(leave -> {
            LeaveWithEmployeeDetails leaveWithDetails = new LeaveWithEmployeeDetails();

            // Copy all fields from LeaveModel to LeaveWithEmployeeDetails
            leaveWithDetails.setId(leave.getId());
            leaveWithDetails.setLeaveId(leave.getLeaveId());
            leaveWithDetails.setEmployeeId(leave.getEmployeeId());
            leaveWithDetails.setCompanyId(leave.getCompanyId());
            leaveWithDetails.setLeaveName(leave.getLeaveName());
            leaveWithDetails.setLeaveType(leave.getLeaveType());
            leaveWithDetails.setStartDate(leave.getStartDate());
            leaveWithDetails.setEndDate(leave.getEndDate());
            leaveWithDetails.setShiftType(leave.getShiftType());
            leaveWithDetails.setReason(leave.getReason());
            leaveWithDetails.setStatus(leave.getStatus());
            leaveWithDetails.setRemarks(leave.getRemarks());
            leaveWithDetails.setCreatedAt(leave.getCreatedAt());

            // Get employee details
            Optional<EmployeeWithLeaveDetailsDTO> employeeOpt = employeeService.getEmployeeById(leave.getEmployeeId());
            if (employeeOpt.isPresent()) {
                EmployeeWithLeaveDetailsDTO employee = employeeOpt.get();
                leaveWithDetails.setEmployeeName(employee.getName());

                // Get department name
                try {
                    if (employee.getDepartment() != null && !employee.getDepartment().isEmpty()) {
                        leaveWithDetails.setDepartment(departmentService.getDepartmentById(employee.getDepartment()).getName());
                    }
                } catch (Exception e) {
                    leaveWithDetails.setDepartment(employee.getDepartment());
                }
            }

            return leaveWithDetails;
        }).collect(Collectors.toList());
    }
}