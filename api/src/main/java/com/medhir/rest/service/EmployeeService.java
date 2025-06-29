package com.medhir.rest.service;

import com.medhir.rest.dto.*;
import com.medhir.rest.service.auth.EmployeeAuthService;
import com.medhir.rest.model.EmployeeModel;
import com.medhir.rest.exception.DuplicateResourceException;
import com.medhir.rest.exception.ResourceNotFoundException;
import com.medhir.rest.model.CompanyModel;
import com.medhir.rest.model.ModuleModel;
import com.medhir.rest.repository.EmployeeRepository;
import com.medhir.rest.repository.ModuleRepository;
import com.medhir.rest.model.settings.DepartmentModel;
import com.medhir.rest.service.settings.DepartmentService;
import com.medhir.rest.model.settings.DesignationModel;
import com.medhir.rest.service.settings.DesignationService;
import com.medhir.rest.model.settings.LeaveTypeModel;
import com.medhir.rest.service.settings.LeaveTypeService;
import com.medhir.rest.model.settings.LeavePolicyModel;
import com.medhir.rest.service.settings.LeavePolicyService;
import com.medhir.rest.utils.GeneratedId;
import com.medhir.rest.utils.MinioService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class EmployeeService {

    @Value("${auth.service.url}")
    String authServiceUrl;
    @Value("${attendance.service.url}")
    String attendanceServiceUrl;

    @Autowired
    private EmployeeRepository employeeRepository;
    @Autowired
    private MinioService minioService;
    @Autowired
    private GeneratedId generatedId;
    @Autowired
    private CompanyService companyService;
    @Autowired
    ModuleRepository moduleRepository;
    @Autowired
    private DesignationService designationService;
    @Autowired
    private DepartmentService departmentService;
    @Autowired
    private EmployeeAuthService employeeAuthService;
    @Autowired
    private LeavePolicyService leavePolicyService;
    @Autowired
    private LeaveTypeService leaveTypeService;

    // Create Employee
    public EmployeeWithLeaveDetailsDTO createEmployee(EmployeeModel employee,
                                                      MultipartFile profileImage,
                                                      MultipartFile aadharImage,
                                                      MultipartFile panImage,
                                                      MultipartFile passportImage,
                                                      MultipartFile drivingLicenseImage,
                                                      MultipartFile voterIdImage,
                                                      MultipartFile passbookImage) {
        if (employeeRepository.findByEmployeeId(employee.getEmployeeId()).isPresent()) {
            throw new DuplicateResourceException("Employee ID already exists: " + employee.getEmployeeId());
        }
        if (employee.getEmailPersonal() != null) {
            if (employeeRepository.findByEmailPersonal(employee.getEmailPersonal()).isPresent()) {
                throw new DuplicateResourceException("Email already exists: " + employee.getEmailPersonal());
            }
        }

        if (employeeRepository.findByPhone(employee.getPhone()).isPresent()) {
            throw new DuplicateResourceException("Phone number already exists : " + employee.getPhone());
        }

        // Set role as EMPLOYEE
        employee.setRoles(Set.of("EMPLOYEE"));

        // Set leave policy based on department
        if (employee.getDepartment() != null && !employee.getDepartment().isEmpty()) {
            DepartmentModel department = departmentService.getDepartmentById(employee.getDepartment());
            LeavePolicyModel leavePolicy = leavePolicyService.getLeavePolicyById(department.getLeavePolicy());

            // Set the leave policy ID
            employee.setLeavePolicyId(department.getLeavePolicy());
        }

        employee = setDefaultValues(employee);

        // Generate image URLs only after validation passes
        if (profileImage != null) {
            employee.setEmployeeImgUrl(minioService.uploadProfileImage(profileImage, employee.getEmployeeId()));
        }
        if (aadharImage != null) {
            employee.getIdProofs()
                    .setAadharImgUrl(minioService.uploadDocumentsImg(aadharImage, employee.getEmployeeId()));
        }
        if (panImage != null) {
            employee.getIdProofs()
                    .setPancardImgUrl(minioService.uploadDocumentsImg(panImage, employee.getEmployeeId()));
        }
        if (passportImage != null) {
            employee.getIdProofs()
                    .setPassportImgUrl(minioService.uploadDocumentsImg(passportImage, employee.getEmployeeId()));
        }
        if (drivingLicenseImage != null) {
            employee.getIdProofs().setDrivingLicenseImgUrl(
                    minioService.uploadDocumentsImg(drivingLicenseImage, employee.getEmployeeId()));
        }
        if (voterIdImage != null) {
            employee.getIdProofs()
                    .setVoterIdImgUrl(minioService.uploadDocumentsImg(voterIdImage, employee.getEmployeeId()));
        }
        if (passbookImage != null) {
            employee.getBankDetails()
                    .setPassbookImgUrl(minioService.uploadDocumentsImg(passbookImage, employee.getEmployeeId()));
        }

        employee.setEmployeeId(generateEmployeeId(employee.getCompanyId()));
        EmployeeModel savedEmployee = employeeRepository.save(employee);

        // Update reporting manager's assignTo list if a reporting manager is set
        if (savedEmployee.getReportingManager() != null && !savedEmployee.getReportingManager().isEmpty()) {
            updateManagerAssignTo(savedEmployee.getReportingManager());
        }

        // Create response DTO with leave details
        EmployeeWithLeaveDetailsDTO response = new EmployeeWithLeaveDetailsDTO();
        BeanUtils.copyProperties(savedEmployee, response);

        // Populate leave policy and type names
        if (savedEmployee.getLeavePolicyId() != null) {
            try {
                LeavePolicyModel leavePolicy = leavePolicyService.getLeavePolicyById(savedEmployee.getLeavePolicyId());
                response.setLeavePolicyName(leavePolicy.getName());

                // Get all leave type names and IDs from the policy
                List<String> leaveTypeNames = new ArrayList<>();
                List<String> leaveTypeIds = new ArrayList<>();

                leavePolicy.getLeaveAllocations().forEach(allocation -> {
                    try {
                        LeaveTypeModel leaveType = leaveTypeService.getLeaveTypeById(allocation.getLeaveTypeId());
                        leaveTypeNames.add(leaveType.getLeaveTypeName());
                        leaveTypeIds.add(leaveType.getLeaveTypeId());
                    } catch (Exception e) {
                        // Skip if leave type not found
                    }
                });

                response.setLeaveTypeNames(leaveTypeNames);
                response.setLeaveTypeIds(leaveTypeIds);
            } catch (Exception e) {
                // If leave policy not found, leave names and IDs as null
            }
        }

        try {
            // Register employee for login with email and phone number as password
            if (savedEmployee.getPhone() != null && !savedEmployee.getPhone().isEmpty() &&
                    savedEmployee.getEmailPersonal() != null && !savedEmployee.getEmailPersonal().isEmpty()) {
                employeeAuthService.registerEmployee(
                        savedEmployee.getEmployeeId(),
                        savedEmployee.getEmailPersonal(),
                        savedEmployee.getPhone());
            }

            // call Attendance Service to register user for face verification
            registerUserInAttendanceService(savedEmployee);
        } catch (Exception e) {
            // Log the error but don't fail the employee creation
            System.err.println("Failed to register employee in auth/attendance service: " + e.getMessage());
        }

        return response;
    }

    // Get All Employees
    public List<EmployeeWithLeaveDetailsDTO> getAllEmployees() {
        List<EmployeeModel> employees = employeeRepository.findAll();
        return employees.stream().map(employee -> {
            EmployeeWithLeaveDetailsDTO dto = new EmployeeWithLeaveDetailsDTO();
            BeanUtils.copyProperties(employee, dto);

            // Get department name
            try {
                if (employee.getDepartment() != null && !employee.getDepartment().isEmpty()) {
                    dto.setDepartmentName(departmentService.getDepartmentById(employee.getDepartment()).getName());
                }
            } catch (Exception e) {
                dto.setDepartmentName(employee.getDepartment());
            }

            // Get designation name
            try {
                Optional<DesignationModel> designation = Optional
                        .ofNullable(designationService.getDesignationById(employee.getDesignation()));
                designation.ifPresent(d -> dto.setDesignationName(d.getName()));
                if (designation.isEmpty()) {
                    dto.setDesignationName(employee.getDesignation());
                }
            } catch (Exception e) {
                dto.setDesignationName(employee.getDesignation());
            }

            // Get leave policy name if available
            if (employee.getLeavePolicyId() != null) {
                try {
                    LeavePolicyModel leavePolicy = leavePolicyService.getLeavePolicyById(employee.getLeavePolicyId());
                    dto.setLeavePolicyName(leavePolicy.getName());

                    // Get all leave type names and IDs from the policy
                    List<String> leaveTypeNames = new ArrayList<>();
                    List<String> leaveTypeIds = new ArrayList<>();

                    leavePolicy.getLeaveAllocations().forEach(allocation -> {
                        try {
                            LeaveTypeModel leaveType = leaveTypeService.getLeaveTypeById(allocation.getLeaveTypeId());
                            leaveTypeNames.add(leaveType.getLeaveTypeName());
                            leaveTypeIds.add(leaveType.getLeaveTypeId());
                        } catch (Exception e) {
                            // Skip if leave type not found
                        }
                    });

                    dto.setLeaveTypeNames(leaveTypeNames);
                    dto.setLeaveTypeIds(leaveTypeIds);
                } catch (Exception e) {
                    // If leave policy not found, leave names and IDs as null
                }
            }

            return dto;
        }).collect(Collectors.toList());
    }

    // Get All Employees with minimal fields (name and employeeId)
    public List<Map<String, String>> getAllEmployeesMinimal() {
        List<EmployeeModel> employees = employeeRepository.findAll();
        return employees.stream()
                .map(employee -> Map.of(
                        "name", employee.getName(),
                        "employeeId", employee.getEmployeeId()))
                .collect(Collectors.toList());
    }

    // Get All Employees by Company ID
    public List<EmployeeWithLeaveDetailsDTO> getEmployeesByCompanyId(String companyId) {
        List<EmployeeModel> employees = employeeRepository.findByCompanyId(companyId);
        return employees.stream().map(employee -> {
            EmployeeWithLeaveDetailsDTO dto = new EmployeeWithLeaveDetailsDTO();
            BeanUtils.copyProperties(employee, dto);

            // Get department name
            try {
                if (employee.getDepartment() != null && !employee.getDepartment().isEmpty()) {
                    dto.setDepartmentName(departmentService.getDepartmentById(employee.getDepartment()).getName());
                }
            } catch (Exception e) {
                dto.setDepartmentName(employee.getDepartment());
            }

            // Get designation name
            try {
                Optional<DesignationModel> designation = Optional
                        .ofNullable(designationService.getDesignationById(employee.getDesignation()));
                designation.ifPresent(d -> dto.setDesignationName(d.getName()));
                if (designation.isEmpty()) {
                    dto.setDesignationName(employee.getDesignation());
                }
            } catch (Exception e) {
                dto.setDesignationName(employee.getDesignation());
            }

            // Get leave policy name if available
            if (employee.getLeavePolicyId() != null) {
                try {
                    LeavePolicyModel leavePolicy = leavePolicyService.getLeavePolicyById(employee.getLeavePolicyId());
                    dto.setLeavePolicyName(leavePolicy.getName());

                    // Get all leave type names and IDs from the policy
                    List<String> leaveTypeNames = new ArrayList<>();
                    List<String> leaveTypeIds = new ArrayList<>();

                    leavePolicy.getLeaveAllocations().forEach(allocation -> {
                        try {
                            LeaveTypeModel leaveType = leaveTypeService.getLeaveTypeById(allocation.getLeaveTypeId());
                            leaveTypeNames.add(leaveType.getLeaveTypeName());
                            leaveTypeIds.add(leaveType.getLeaveTypeId());
                        } catch (Exception e) {
                            // Skip if leave type not found
                        }
                    });

                    dto.setLeaveTypeNames(leaveTypeNames);
                    dto.setLeaveTypeIds(leaveTypeIds);
                } catch (Exception e) {
                    // If leave policy not found, leave names and IDs as null
                }
            }

            return dto;
        }).collect(Collectors.toList());
    }

    // Get Employee By EmployeeId
    public Optional<EmployeeWithLeaveDetailsDTO> getEmployeeById(String employeeId) {
        return employeeRepository.findByEmployeeId(employeeId).map(employee -> {
            EmployeeWithLeaveDetailsDTO dto = new EmployeeWithLeaveDetailsDTO();
            BeanUtils.copyProperties(employee, dto);

            // Get department name
            try {
                if (employee.getDepartment() != null && !employee.getDepartment().isEmpty()) {
                    dto.setDepartmentName(departmentService.getDepartmentById(employee.getDepartment()).getName());
                }
            } catch (Exception e) {
                dto.setDepartmentName(employee.getDepartment());
            }

            // Get designation name
            try {
                Optional<DesignationModel> designation = Optional
                        .ofNullable(designationService.getDesignationById(employee.getDesignation()));
                designation.ifPresent(d -> dto.setDesignationName(d.getName()));
                if (designation.isEmpty()) {
                    dto.setDesignationName(employee.getDesignation());
                }
            } catch (Exception e) {
                dto.setDesignationName(employee.getDesignation());
            }

            // Get leave policy name if available
            if (employee.getLeavePolicyId() != null) {
                try {
                    LeavePolicyModel leavePolicy = leavePolicyService.getLeavePolicyById(employee.getLeavePolicyId());
                    dto.setLeavePolicyName(leavePolicy.getName());

                    // Get all leave type names and IDs from the policy
                    List<String> leaveTypeNames = new ArrayList<>();
                    List<String> leaveTypeIds = new ArrayList<>();

                    leavePolicy.getLeaveAllocations().forEach(allocation -> {
                        try {
                            LeaveTypeModel leaveType = leaveTypeService.getLeaveTypeById(allocation.getLeaveTypeId());
                            leaveTypeNames.add(leaveType.getLeaveTypeName());
                            leaveTypeIds.add(leaveType.getLeaveTypeId());
                        } catch (Exception e) {
                            // Skip if leave type not found
                        }
                    });

                    dto.setLeaveTypeNames(leaveTypeNames);
                    dto.setLeaveTypeIds(leaveTypeIds);
                } catch (Exception e) {
                    // If leave policy not found, leave names and IDs as null
                }
            }

            return dto;
        });
    }

    // Get Employees by Manager
    public List<ManagerEmployeeDTO> getEmployeesByManager(String managerId) {
        List<EmployeeModel> employees = employeeRepository.findByReportingManager(managerId);

        return employees.stream()
                .map(employee -> {
                    ManagerEmployeeDTO dto = new ManagerEmployeeDTO();
                    dto.setEmployeeId(employee.getEmployeeId());
                    dto.setName(employee.getName());
                    dto.setFathersName(employee.getFathersName());
                    dto.setPhone(employee.getPhone());
                    dto.setEmailOfficial(employee.getEmailOfficial());
                    dto.setJoiningDate(employee.getJoiningDate());
                    dto.setCurrentAddress(employee.getCurrentAddress());
                    dto.setRoles(employee.getRoles());

                    // Get designation name from designation service
                    try {
                        Optional<DesignationModel> designation = Optional
                                .ofNullable(designationService.getDesignationById(employee.getDesignation()));
                        designation.ifPresent(d -> dto.setDesignationName(d.getName()));
                        if (designation.isEmpty()) {
                            dto.setDesignationName(employee.getDesignation());
                        }
                    } catch (Exception e) {
                        dto.setDesignationName(employee.getDesignation());
                    }

                    // Get department name from department service
                    try {
                        if (employee.getDepartment() != null && !employee.getDepartment().isEmpty()) {
                            dto.setDepartmentName(
                                    departmentService.getDepartmentById(employee.getDepartment()).getName());
                        }
                    } catch (Exception e) {
                        dto.setDepartmentName(employee.getDepartment());
                    }

                    return dto;
                })
                .collect(Collectors.toList());
    }

    // Delete Employee by Employee ID
    public void deleteEmployee(String employeeId) {
        Optional<EmployeeModel> employeeOpt = employeeRepository.findByEmployeeId(employeeId);
        if (employeeOpt.isEmpty()) {
            throw new ResourceNotFoundException("Employee not found with ID: " + employeeId);
        }

        // Delete the employee
        employeeRepository.delete(employeeOpt.get());
    }

    // Update Employee
    public EmployeeWithLeaveDetailsDTO updateEmployee(String employeeId, EmployeeModel updatedEmployee,
            MultipartFile profileImage,
            MultipartFile aadharImage,
            MultipartFile panImage,
            MultipartFile passportImage,
            MultipartFile drivingLicenseImage,
            MultipartFile voterIdImage,
            MultipartFile passbookImage) {
        return employeeRepository.findByEmployeeId(employeeId).map(existingEmployee -> {

            Optional<EmployeeModel> employeeIDExists = employeeRepository
                    .findByEmployeeId(updatedEmployee.getEmployeeId());
            if (employeeIDExists.isPresent() && !employeeIDExists.get().getEmployeeId().equals(employeeId)) {
                throw new DuplicateResourceException("Employee ID already exists: " + updatedEmployee.getEmployeeId());
            }

            if (updatedEmployee.getEmailPersonal() != null) {
                Optional<EmployeeModel> emailExists = employeeRepository
                        .findByEmailPersonal(updatedEmployee.getEmailPersonal());
                if (emailExists.isPresent() && !emailExists.get().getEmployeeId().equals(employeeId)) {
                    throw new DuplicateResourceException(
                            emailExists.get().getEmailPersonal() + " : Email is already in use by another Employee");
                }
            }

            Optional<EmployeeModel> phoneExists = employeeRepository.findByPhone(updatedEmployee.getPhone());
            if (phoneExists.isPresent() && !phoneExists.get().getEmployeeId().equals(employeeId)) {
                throw new DuplicateResourceException(
                        phoneExists.get().getPhone() + " : Phone number is already in use by another Employee");
            }

            // Store old reporting manager for comparison
            String oldReportingManager = existingEmployee.getReportingManager();

            // Update basic details
            existingEmployee.setName(updatedEmployee.getName());
            existingEmployee.setDesignation(updatedEmployee.getDesignation());
            existingEmployee.setFathersName(updatedEmployee.getFathersName());
            existingEmployee.setOvertimeEligibile(updatedEmployee.isOvertimeEligibile());
            existingEmployee.setPfEnrolled(updatedEmployee.isPfEnrolled());
            existingEmployee.setUanNumber(updatedEmployee.getUanNumber());
            existingEmployee.setEsicEnrolled(updatedEmployee.isEsicEnrolled());
            existingEmployee.setEsicNumber(updatedEmployee.getEsicNumber());
            existingEmployee.setWeeklyOffs(updatedEmployee.getWeeklyOffs());
            existingEmployee.setEmailPersonal(updatedEmployee.getEmailPersonal());
            existingEmployee.setEmailOfficial(updatedEmployee.getEmailOfficial());
            existingEmployee.setPhone(updatedEmployee.getPhone());
            existingEmployee.setAlternatePhone(updatedEmployee.getAlternatePhone());
            existingEmployee.setDepartment(updatedEmployee.getDepartment());
            existingEmployee.setGender(updatedEmployee.getGender());
            existingEmployee.setReportingManager(updatedEmployee.getReportingManager());
            existingEmployee.setPermanentAddress(updatedEmployee.getPermanentAddress());
            existingEmployee.setCurrentAddress(updatedEmployee.getCurrentAddress());
            existingEmployee.setSalaryDetails(updatedEmployee.getSalaryDetails());
            existingEmployee.setJoiningDate(updatedEmployee.getJoiningDate());

            // Update leave policy based on department
            if (updatedEmployee.getDepartment() != null && !updatedEmployee.getDepartment().isEmpty()) {
                DepartmentModel department = departmentService.getDepartmentById(updatedEmployee.getDepartment());
                LeavePolicyModel leavePolicy = leavePolicyService.getLeavePolicyById(department.getLeavePolicy());

                // Set the leave policy ID
                existingEmployee.setLeavePolicyId(department.getLeavePolicy());
            }

            // Update Bank Details
            if (updatedEmployee.getBankDetails() != null) {
                if (existingEmployee.getBankDetails() == null) {
                    existingEmployee.setBankDetails(new EmployeeModel.BankDetails());
                }
                existingEmployee.getBankDetails().setAccountNumber(updatedEmployee.getBankDetails().getAccountNumber());
                existingEmployee.getBankDetails()
                        .setAccountHolderName(updatedEmployee.getBankDetails().getAccountHolderName());
                existingEmployee.getBankDetails().setIfscCode(updatedEmployee.getBankDetails().getIfscCode());
                existingEmployee.getBankDetails().setBankName(updatedEmployee.getBankDetails().getBankName());
                existingEmployee.getBankDetails().setBranchName(updatedEmployee.getBankDetails().getBranchName());
                existingEmployee.getBankDetails().setUpiId(updatedEmployee.getBankDetails().getUpiId());
                existingEmployee.getBankDetails()
                        .setUpiPhoneNumber(updatedEmployee.getBankDetails().getUpiPhoneNumber());
            }

            // Update ID Proofs
            if (updatedEmployee.getIdProofs() != null) {
                if (existingEmployee.getIdProofs() == null) {
                    existingEmployee.setIdProofs(new EmployeeModel.IdProofs());
                }
                existingEmployee.getIdProofs().setAadharNo(updatedEmployee.getIdProofs().getAadharNo());
                existingEmployee.getIdProofs().setPanNo(updatedEmployee.getIdProofs().getPanNo());
                existingEmployee.getIdProofs().setPassport(updatedEmployee.getIdProofs().getPassport());
                existingEmployee.getIdProofs().setDrivingLicense(updatedEmployee.getIdProofs().getDrivingLicense());
                existingEmployee.getIdProofs().setVoterId(updatedEmployee.getIdProofs().getVoterId());
            }

            // Update Salary Details
            if (updatedEmployee.getSalaryDetails() != null) {
                if (existingEmployee.getSalaryDetails() == null) {
                    existingEmployee.setSalaryDetails(new EmployeeModel.SalaryDetails());
                }
                existingEmployee.getSalaryDetails().setAnnualCtc(updatedEmployee.getSalaryDetails().getAnnualCtc());
                existingEmployee.getSalaryDetails().setMonthlyCtc(updatedEmployee.getSalaryDetails().getMonthlyCtc());
                existingEmployee.getSalaryDetails().setBasicSalary(updatedEmployee.getSalaryDetails().getBasicSalary());
                existingEmployee.getSalaryDetails().setHra(updatedEmployee.getSalaryDetails().getHra());
                existingEmployee.getSalaryDetails().setAllowances(updatedEmployee.getSalaryDetails().getAllowances());
                existingEmployee.getSalaryDetails()
                        .setEmployerPfContribution(updatedEmployee.getSalaryDetails().getEmployerPfContribution());
                existingEmployee.getSalaryDetails()
                        .setEmployeePfContribution(updatedEmployee.getSalaryDetails().getEmployeePfContribution());
            }

            // Preserve existing images or update if a new image is uploaded
            if (profileImage != null) {
                existingEmployee.setEmployeeImgUrl(
                        minioService.uploadProfileImage(profileImage, existingEmployee.getEmployeeId()));
            }

            if (existingEmployee.getIdProofs() == null) {
                existingEmployee.setIdProofs(new EmployeeModel.IdProofs());
            }

            if (aadharImage != null) {
                existingEmployee.getIdProofs().setAadharImgUrl(
                        minioService.uploadDocumentsImg(aadharImage, existingEmployee.getEmployeeId()));
            }

            if (panImage != null) {
                existingEmployee.getIdProofs()
                        .setPancardImgUrl(minioService.uploadDocumentsImg(panImage, existingEmployee.getEmployeeId()));
            }

            if (passportImage != null) {
                existingEmployee.getIdProofs().setPassportImgUrl(
                        minioService.uploadDocumentsImg(passportImage, existingEmployee.getEmployeeId()));
            }

            if (drivingLicenseImage != null) {
                existingEmployee.getIdProofs().setDrivingLicenseImgUrl(
                        minioService.uploadDocumentsImg(drivingLicenseImage, existingEmployee.getEmployeeId()));
            }

            if (voterIdImage != null) {
                existingEmployee.getIdProofs().setVoterIdImgUrl(
                        minioService.uploadDocumentsImg(voterIdImage, existingEmployee.getEmployeeId()));
            }

            if (existingEmployee.getBankDetails() == null) {
                existingEmployee.setBankDetails(new EmployeeModel.BankDetails());
            }

            if (passbookImage != null) {
                existingEmployee.getBankDetails().setPassbookImgUrl(
                        minioService.uploadDocumentsImg(passbookImage, existingEmployee.getEmployeeId()));
            }

            existingEmployee = setDefaultValues(existingEmployee);
            // call Attendance Service to update user for face verification
            updateEmployeeInAttendanceService(existingEmployee);

            EmployeeModel savedEmployee = employeeRepository.save(existingEmployee);

            // Create response DTO with leave details
            EmployeeWithLeaveDetailsDTO response = new EmployeeWithLeaveDetailsDTO();
            BeanUtils.copyProperties(savedEmployee, response);

            // Populate leave policy and type names
            if (savedEmployee.getLeavePolicyId() != null) {
                try {
                    LeavePolicyModel leavePolicy = leavePolicyService
                            .getLeavePolicyById(savedEmployee.getLeavePolicyId());
                    response.setLeavePolicyName(leavePolicy.getName());

                    // Get all leave type names and IDs from the policy
                    List<String> leaveTypeNames = new ArrayList<>();
                    List<String> leaveTypeIds = new ArrayList<>();

                    leavePolicy.getLeaveAllocations().forEach(allocation -> {
                        try {
                            LeaveTypeModel leaveType = leaveTypeService.getLeaveTypeById(allocation.getLeaveTypeId());
                            leaveTypeNames.add(leaveType.getLeaveTypeName());
                            leaveTypeIds.add(leaveType.getLeaveTypeId());
                        } catch (Exception e) {
                            // Skip if leave type not found
                        }
                    });

                    response.setLeaveTypeNames(leaveTypeNames);
                    response.setLeaveTypeIds(leaveTypeIds);
                } catch (Exception e) {
                    // If leave policy not found, leave names and IDs as null
                }
            }

            // If reporting manager changed, update both old and new manager's assignTo lists
            String newReportingManager = updatedEmployee.getReportingManager() != null ? updatedEmployee.getReportingManager() : "";
            if (!newReportingManager.equals(oldReportingManager)) {
                // Update old manager's assignTo list (remove this employee)
                updateManagerAssignTo(oldReportingManager);
                // Update new manager's assignTo list (add this employee)
                updateManagerAssignTo(newReportingManager);
            }

            return response;
        }).orElseThrow(() -> new ResourceNotFoundException("Employee with ID " + employeeId + " not found"));
    }

    // Set default values for missing fields
    private EmployeeModel setDefaultValues(EmployeeModel employee) {
        if (employee.getName() == null)
            employee.setName("");
        if (employee.getDesignation() == null)
            employee.setDesignation("");
        if (employee.getEmailPersonal() == null)
            employee.setEmailPersonal("");
        if (employee.getPhone() == null)
            employee.setPhone("");
        if (employee.getAlternatePhone() == null)
            employee.setAlternatePhone("");
        if (employee.getDepartment() == null)
            employee.setDepartment("");
        if (employee.getGender() == null)
            employee.setGender("");
        if (employee.getReportingManager() == null)
            employee.setReportingManager("");
        if (employee.getPermanentAddress() == null)
            employee.setPermanentAddress("");
        if (employee.getCurrentAddress() == null)
            employee.setCurrentAddress("");

        // Initialize assignTo list if null
        if (employee.getAssignTo() == null) {
            employee.setAssignTo(new ArrayList<>());
        }

        // Update assignTo lists for all managers
        updateAllManagersAssignTo();

        // ID Proofs
        if (employee.getIdProofs() == null) {
            employee.setIdProofs(new EmployeeModel.IdProofs());
        } else {
            if (employee.getIdProofs().getAadharNo() == null)
                employee.getIdProofs().setAadharNo("");
            if (employee.getIdProofs().getPanNo() == null)
                employee.getIdProofs().setPanNo("");
            if (employee.getIdProofs().getPassport() == null)
                employee.getIdProofs().setPassport("");
            if (employee.getIdProofs().getDrivingLicense() == null)
                employee.getIdProofs().setDrivingLicense("");
            if (employee.getIdProofs().getVoterId() == null)
                employee.getIdProofs().setVoterId("");
        }

        // Bank Details
        if (employee.getBankDetails() == null) {
            employee.setBankDetails(new EmployeeModel.BankDetails());
        } else {
            if (employee.getBankDetails().getAccountNumber() == null)
                employee.getBankDetails().setAccountNumber("");
            if (employee.getBankDetails().getAccountHolderName() == null)
                employee.getBankDetails().setAccountHolderName("");
            if (employee.getBankDetails().getIfscCode() == null)
                employee.getBankDetails().setIfscCode("");
            if (employee.getBankDetails().getBankName() == null)
                employee.getBankDetails().setBankName("");
            if (employee.getBankDetails().getBranchName() == null)
                employee.getBankDetails().setBranchName("");
            if (employee.getBankDetails().getUpiId() == null)
                employee.getBankDetails().setUpiId("");
            if (employee.getBankDetails().getUpiPhoneNumber() == null)
                employee.getBankDetails().setUpiPhoneNumber("");
        }

        // Salary Details
        if (employee.getSalaryDetails() == null) {
            employee.setSalaryDetails(new EmployeeModel.SalaryDetails());
        } else {
            if (employee.getSalaryDetails().getAnnualCtc() == null)
                employee.getSalaryDetails().setAnnualCtc(0.0);
            if (employee.getSalaryDetails().getMonthlyCtc() == null)
                employee.getSalaryDetails().setMonthlyCtc(0.0);
            if (employee.getSalaryDetails().getBasicSalary() == null)
                employee.getSalaryDetails().setBasicSalary(0.0);
            if (employee.getSalaryDetails().getHra() == null)
                employee.getSalaryDetails().setHra(0.0);
            if (employee.getSalaryDetails().getAllowances() == null)
                employee.getSalaryDetails().setAllowances(0.0);
            if (employee.getSalaryDetails().getEmployerPfContribution() == null)
                employee.getSalaryDetails().setEmployerPfContribution(0.0);
            if (employee.getSalaryDetails().getEmployeePfContribution() == null)
                employee.getSalaryDetails().setEmployeePfContribution(0.0);
        }

        return employee;
    }

    private void registerUserInAuthService(EmployeeModel employee) {

        Map<String, String> request = new HashMap<>();
        request.put("employeeId", employee.getEmployeeId()); // Assuming getId() returns employee ID
        request.put("username", employee.getName()); // Username = Employee Name
        request.put("password", employee.getName()); // Password = Employee Name

        RestTemplate restTemplate = new RestTemplate();
        try {
            restTemplate.postForEntity(authServiceUrl, request, String.class);
            System.out.println("User registered in Auth Service: " + employee.getName());
        } catch (Exception e) {
            System.err.println("Failed to register user in Auth Service: " + e.getMessage());
        }
    }

    private RestTemplate restTemplate = new RestTemplate();

    public void registerUserInAttendanceService(EmployeeModel employee) {
        try {
            // Create request parameters
            MultiValueMap<String, String> requestBody = new LinkedMultiValueMap<>();
            requestBody.add("employeeId", employee.getEmployeeId());
            requestBody.add("name", employee.getName());
            requestBody.add("imgUrl", employee.getEmployeeImgUrl()); // Always using imgUrl
            requestBody.add("joiningDate", employee.getJoiningDate().toString());

            // Set headers for form-data
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA); // Ensures compatibility with @RequestParam
            HttpEntity<MultiValueMap<String, String>> requestEntity = new HttpEntity<>(requestBody, headers);

            // Call the /register API
            restTemplate.postForEntity(attendanceServiceUrl + "/register", requestEntity, String.class);
            System.out.println("User registered in Attendance Service: " + employee.getName());

        } catch (Exception e) {
            System.err.println("Failed to register user in Attendance Service: " + e.getMessage());
        }
    }

    public void updateEmployeeInAttendanceService(EmployeeModel employee) {
        try {
            // Create request parameters
            MultiValueMap<String, String> requestBody = new LinkedMultiValueMap<>();
            requestBody.add("employeeId", employee.getEmployeeId());

            if (employee.getName() != null && !employee.getName().trim().isEmpty()) {
                requestBody.add("name", employee.getName());
            }

            if (employee.getEmployeeImgUrl() != null && !employee.getEmployeeImgUrl().trim().isEmpty()) {
                requestBody.add("imgUrl", employee.getEmployeeImgUrl());
            }

            if (employee.getJoiningDate() != null) {
                requestBody.add("joiningDate", employee.getJoiningDate().toString());
            }

            // Set headers for form-data
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            HttpEntity<MultiValueMap<String, String>> requestEntity = new HttpEntity<>(requestBody, headers);

            // Make the PUT request
            ResponseEntity<String> response = restTemplate.exchange(
                    attendanceServiceUrl + "/update", HttpMethod.PUT, requestEntity, String.class);
            System.out.println("User Updated in Attendance Service: " + employee.getName());

        } catch (Exception e) {
            System.err.println("Failed to Update user in Attendance Service: " + e.getMessage());

        }
    }

    public String generateEmployeeId(String companyId) {
        CompanyModel company = companyService.getCompanyById(companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Company not found with ID: " + companyId));

        String prefix = company.getPrefixForEmpID();

        return generatedId.generateId(prefix, EmployeeModel.class, "employeeId");
    }

    // Register Admin as Employee
    public EmployeeModel registerAdminAsEmployee(RegisterAdminRequest request) {
        // Create a new employee model
        EmployeeModel employee = new EmployeeModel();

        // Set basic details from the request
        employee.setName(request.getName());
        employee.setEmailPersonal(request.getEmail());
        employee.setPhone(request.getPhone());
        employee.setCompanyId(request.getCompanyId());

        // Set roles as both EMPLOYEE and HRADMIN
        employee.setRoles(Set.of("EMPLOYEE", "HRADMIN"));

        // Generate employee ID
        employee.setEmployeeId(generateEmployeeId(request.getCompanyId()));

        // Set default values for required fields
        employee = setDefaultValues(employee);

        // Save the employee
        EmployeeModel savedEmployee = employeeRepository.save(employee);

        // Register employee for login with email
        employeeAuthService.registerEmployee(
                savedEmployee.getEmployeeId(),
                savedEmployee.getEmailPersonal(),
                savedEmployee.getPhone());

        // Register user in attendance service
        registerUserInAttendanceService(savedEmployee);

        return savedEmployee;
    }

    public List<UserCompanyDTO> getEmployeeCompanies(String employeeId) {
        EmployeeModel employee = employeeRepository.findByEmployeeId(employeeId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee with ID " + employeeId + " not found"));

        if (employee.getModuleIds() == null || employee.getModuleIds().isEmpty()) {
            return List.of();
        }

        // Get all modules for the employee
        List<ModuleModel> modules = employee.getModuleIds().stream()
                .map(moduleId -> moduleRepository.findByModuleId(moduleId))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();

        // Get unique company IDs from modules
        Set<String> companyIds = modules.stream()
                .filter(module -> module.getCompanyId() != null)
                .map(ModuleModel::getCompanyId)
                .collect(Collectors.toSet());

        // Get company details for each company ID
        return companyIds.stream()
                .map(companyId -> {
                    try {
                        Optional<CompanyModel> company = companyService.getCompanyById(companyId);
                        return new UserCompanyDTO(
                                company.get().getCompanyId(),
                                company.get().getName(),
                                company.get().getColorCode());
                    } catch (ResourceNotFoundException e) {
                        return new UserCompanyDTO(companyId, "Unknown Company", "Unknown Color");
                    }
                })
                .collect(Collectors.toList());
    }

    public List<Map<String, String>> getManagersByDepartment(String departmentId) {
        // Get all designations in the department that have isManager=true
        List<DesignationModel> managerDesignations = designationService.getDesignationsByDepartment(departmentId)
                .stream()
                .filter(DesignationModel::isManager)
                .collect(Collectors.toList());

        // Get all designation IDs
        List<String> managerDesignationIds = managerDesignations.stream()
                .map(DesignationModel::getDesignationId)
                .collect(Collectors.toList());

        // Get all employees with these designations
        List<EmployeeModel> managers = employeeRepository.findByDepartmentAndDesignationIn(departmentId,
                managerDesignationIds);

        // Map to required format (name and employeeId)
        return managers.stream()
                .map(manager -> Map.of(
                        "name", manager.getName(),
                        "employeeId", manager.getEmployeeId()))
                .collect(Collectors.toList());
    }

    // Get All Employees by Company ID with additional details
    public List<CompanyEmployeeDTO> getAllEmployeesByCompanyIdWithDetails(String companyId) {
        List<EmployeeModel> employees = employeeRepository.findByCompanyId(companyId);
        return employees.stream().map(employee -> {
            CompanyEmployeeDTO dto = new CompanyEmployeeDTO(employee);

            // Get department name
            try {
                if (employee.getDepartment() != null && !employee.getDepartment().isEmpty()) {
                    dto.setDepartmentName(departmentService.getDepartmentById(employee.getDepartment()).getName());
                }
            } catch (Exception e) {
                dto.setDepartmentName(employee.getDepartment());
            }

            // Get designation name
            try {
                Optional<DesignationModel> designation = Optional
                        .ofNullable(designationService.getDesignationById(employee.getDesignation()));
                designation.ifPresent(d -> dto.setDesignationName(d.getName()));
                if (designation.isEmpty()) {
                    dto.setDesignationName(employee.getDesignation());
                }
            } catch (Exception e) {
                dto.setDesignationName(employee.getDesignation());
            }

            // Get reporting manager name
            if (employee.getReportingManager() != null && !employee.getReportingManager().isEmpty()) {
                employeeRepository.findByEmployeeId(employee.getReportingManager())
                        .ifPresent(manager -> dto.setReportingManagerName(manager.getName()));
            }

            // Get leave policy name and leave type names if available
            if (employee.getLeavePolicyId() != null) {
                try {
                    LeavePolicyModel leavePolicy = leavePolicyService.getLeavePolicyById(employee.getLeavePolicyId());
                    dto.setLeavePolicyName(leavePolicy.getName());

                    // Get all leave type names and IDs from the policy
                    List<String> leaveTypeNames = new ArrayList<>();
                    List<String> leaveTypeIds = new ArrayList<>();

                    leavePolicy.getLeaveAllocations().forEach(allocation -> {
                        try {
                            LeaveTypeModel leaveType = leaveTypeService.getLeaveTypeById(allocation.getLeaveTypeId());
                            leaveTypeNames.add(leaveType.getLeaveTypeName());
                            leaveTypeIds.add(leaveType.getLeaveTypeId());
                        } catch (Exception e) {
                            // Skip if leave type not found
                        }
                    });

                    dto.setLeaveTypeNames(leaveTypeNames);
                    dto.setLeaveTypeIds(leaveTypeIds);
                } catch (Exception e) {
                    // If leave policy not found, leave names and IDs as null
                }
            }

            // Copy assignTo list from employee model
            dto.setAssignTo(employee.getAssignTo());

            return dto;
        }).collect(Collectors.toList());
    }

    public EmployeeModel updateEmployeeRole(String employeeId, List<String> roles, String operation, String companyId) {
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("Roles list cannot be null or empty");
        }

        // Get the employee
        EmployeeModel employee = employeeRepository.findByEmployeeId(employeeId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found with ID: " + employeeId));

        // Get current roles or initialize
        Set<String> currentRoles = Optional.ofNullable(employee.getRoles())
                .map(HashSet::new)
                .orElseGet(HashSet::new);

        // Normalize operation
        String op = operation == null ? "" : operation.trim().toUpperCase();

        // Find all HR modules for the provided companyId
        List<ModuleModel> hrModules = moduleRepository.findAll().stream()
                .filter(m -> m.getCompanyId() != null && m.getCompanyId().equals(companyId)
                        && m.getModuleName() != null && m.getModuleName().toUpperCase().contains("HR"))
                .collect(Collectors.toList());

        switch (op) {
            case "ADD":
                currentRoles.addAll(roles);
                // If HRADMIN is being added, attach HR modules
                if (roles.contains("HRADMIN")) {
                    if (employee.getModuleIds() == null) {
                        employee.setModuleIds(new ArrayList<>());
                    }
                    for (ModuleModel hrModule : hrModules) {
                        if (!employee.getModuleIds().contains(hrModule.getModuleId())) {
                            employee.getModuleIds().add(hrModule.getModuleId());
                        }
                        // Also add this employee to the module's employeeIds if not present
                        if (hrModule.getEmployeeIds() == null) {
                            hrModule.setEmployeeIds(new ArrayList<>());
                        }
                        if (!hrModule.getEmployeeIds().contains(employeeId)) {
                            hrModule.getEmployeeIds().add(employeeId);
                            moduleRepository.save(hrModule);
                        }
                    }
                }
                break;
            case "REMOVE":
                currentRoles.removeAll(roles);
                // If HRADMIN is being removed, detach HR modules
                if (roles.contains("HRADMIN")) {
                    if (employee.getModuleIds() != null) {
                        for (ModuleModel hrModule : hrModules) {
                            employee.getModuleIds().remove(hrModule.getModuleId());
                            // Remove this employee from the module's employeeIds
                            if (hrModule.getEmployeeIds() != null) {
                                hrModule.getEmployeeIds().remove(employeeId);
                                // Save the module even if employeeIds is now empty
                                moduleRepository.save(hrModule);
                            }
                            // No error or exception if no admin remains
                        }
                    }
                }
                break;
            default:
                throw new IllegalArgumentException("Invalid operation. Must be 'ADD' or 'REMOVE'");
        }

        employee.setRoles(currentRoles);
        return employeeRepository.save(employee);
    }

    public EmployeeAttendanceDetailsDTO getEmployeeAttendanceDetails(String employeeId) {
        EmployeeModel employee = employeeRepository.findByEmployeeId(employeeId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found with ID: " + employeeId));

        EmployeeAttendanceDetailsDTO dto = new EmployeeAttendanceDetailsDTO();
        dto.setName(employee.getName());
        dto.setEmployeeImgUrl(employee.getEmployeeImgUrl());
        dto.setJoiningDate(employee.getJoiningDate());
        dto.setWeeklyOffs(employee.getWeeklyOffs());

        return dto;
    }

    // Add this new method
    private void updateAllManagersAssignTo() {
        // Get all employees
        List<EmployeeModel> allEmployees = employeeRepository.findAll();

        // Create a map of reporting manager to their team members
        Map<String, List<String>> managerToTeamMap = new HashMap<>();

        // First, collect all team members for each manager based on reportingManager
        // field
        for (EmployeeModel employee : allEmployees) {
            if (employee.getReportingManager() != null && !employee.getReportingManager().isEmpty()) {
                managerToTeamMap.computeIfAbsent(employee.getReportingManager(), k -> new ArrayList<>())
                        .add(employee.getEmployeeId());
            }
        }

        // Then update each manager's assignTo list and add MANAGER role
        for (Map.Entry<String, List<String>> entry : managerToTeamMap.entrySet()) {
            String managerId = entry.getKey();
            List<String> teamMembers = entry.getValue();

            employeeRepository.findByEmployeeId(managerId).ifPresent(manager -> {
                boolean needsUpdate = false;

                // Update assignTo list
                if (!teamMembers.equals(manager.getAssignTo())) {
                    manager.setAssignTo(teamMembers);
                    needsUpdate = true;
                }

                // Add MANAGER role if not already present
                Set<String> roles = manager.getRoles();
                if (roles == null) {
                    roles = new HashSet<>();
                }
                if (!roles.contains("MANAGER")) {
                    roles.add("MANAGER");
                    manager.setRoles(roles);
                    needsUpdate = true;
                }

                // Save if any changes were made
                if (needsUpdate) {
                    employeeRepository.save(manager);
                }
            });
        }

        // Also handle direct assignTo relationships
        for (EmployeeModel employee : allEmployees) {
            if (employee.getAssignTo() != null && !employee.getAssignTo().isEmpty()) {
                boolean needsUpdate = false;

                // Add MANAGER role if not already present
                Set<String> roles = employee.getRoles();
                if (roles == null) {
                    roles = new HashSet<>();
                }
                if (!roles.contains("MANAGER")) {
                    roles.add("MANAGER");
                    employee.setRoles(roles);
                    needsUpdate = true;
                }

                // Save if any changes were made
                if (needsUpdate) {
                    employeeRepository.save(employee);
                }
            }
        }
    }

    // Add this new method
    private void updateManagerAssignTo(String managerId) {
        if (managerId == null || managerId.isEmpty()) {
            return;
        }

        // Get all employees who report to this manager
        List<EmployeeModel> teamMembers = employeeRepository.findByReportingManager(managerId);

        // Get the manager
        employeeRepository.findByEmployeeId(managerId).ifPresent(manager -> {
            boolean needsUpdate = false;

            // Create list of team member IDs
            List<String> teamMemberIds = teamMembers.stream()
                    .map(EmployeeModel::getEmployeeId)
                    .collect(Collectors.toList());

            // Update assignTo list if different
            if (!teamMemberIds.equals(manager.getAssignTo())) {
                manager.setAssignTo(teamMemberIds);
                needsUpdate = true;
            }

            // Handle MANAGER role based on assignTo list
            Set<String> roles = manager.getRoles();
            if (roles == null) {
                roles = new HashSet<>();
            }

            if (teamMemberIds.isEmpty()) {
                // Remove MANAGER role if assignTo is empty
                if (roles.contains("MANAGER")) {
                    roles.remove("MANAGER");
                    manager.setRoles(roles);
                    needsUpdate = true;
                }
            } else {
                // Add MANAGER role if not already present and assignTo is not empty
                if (!roles.contains("MANAGER")) {
                    roles.add("MANAGER");
                    manager.setRoles(roles);
                    needsUpdate = true;
                }
            }

            // Save if any changes were made
            if (needsUpdate) {
                employeeRepository.save(manager);
            }
        });
    }

    public EmployeeLeavePolicyWeeklyOffsDTO getEmployeeLeavePolicy(String employeeId) {
        EmployeeModel employee = employeeRepository.findByEmployeeId(employeeId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found with ID: " + employeeId));

        EmployeeLeavePolicyWeeklyOffsDTO dto = new EmployeeLeavePolicyWeeklyOffsDTO();
        dto.setEmployeeId(employee.getEmployeeId());
        dto.setEmployeeName(employee.getName());
        dto.setWeeklyOffs(employee.getWeeklyOffs());

        if (employee.getLeavePolicyId() != null) {
            try {
                LeavePolicyModel leavePolicy = leavePolicyService.getLeavePolicyById(employee.getLeavePolicyId());
                dto.setLeavePolicyId(leavePolicy.getLeavePolicyId());
                dto.setLeavePolicyName(leavePolicy.getName());
                dto.setLeaveAllocations(leavePolicy.getLeaveAllocations());
            } catch (Exception e) {
                // If leave policy not found, leave policy details will be null
            }
        }

        return dto;
    }

}
