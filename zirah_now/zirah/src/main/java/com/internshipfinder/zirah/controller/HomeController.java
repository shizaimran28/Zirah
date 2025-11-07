package com.internshipfinder.zirah.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.internshipfinder.zirah.model.Student;
import com.internshipfinder.zirah.model.Recruiter;
import com.internshipfinder.zirah.model.Internship;
import com.internshipfinder.zirah.model.ApplicationStatus;
import com.internshipfinder.zirah.service.InternshipService;
import com.internshipfinder.zirah.service.StudentService;
import com.internshipfinder.zirah.service.RecruiterService;
import jakarta.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Controller
public class HomeController {

    @Autowired
    private InternshipService internshipService;

    @Autowired
    private StudentService studentService;

    @Autowired
    private RecruiterService recruiterService;

    // Add default constructor to prevent instantiation issues
    public HomeController() {
        // Default constructor
    }

    @GetMapping("/")
    public String welcome() {
        return "welcome";
    }

    @GetMapping("/home")
    public String home(Model model) {
        model.addAttribute("internships", internshipService.getAllInternships());
        return "home";
    }

    @GetMapping("/signup")
    public String signup(Model model) {
        model.addAttribute("student", new Student());
        model.addAttribute("recruiter", new Recruiter());
        return "signup";
    }

    @GetMapping("/signin")
    public String signin() {
        return "signin";
    }

    @GetMapping("/student/dashboard")
    public String studentDashboard(Model model, HttpSession session) {
        Object user = session.getAttribute("user");
        
        // Check if user is a student
        if (user instanceof Student) {
            Student student = (Student) user;
            
            // Refresh student data to get latest notifications and resume info
            Student freshStudent = studentService.findById(student.getId());
            if (freshStudent != null) {
                session.setAttribute("user", freshStudent);
                student = freshStudent;
            }

            // Get internships with null safety
            List<Internship> internships = internshipService.getAllInternships();
            if (internships == null) {
                internships = new ArrayList<>();
            }

            // Get my applications with null safety
            List<Internship> myApplications = internshipService.getInternshipsByStudent(student.getId());
            if (myApplications == null) {
                myApplications = new ArrayList<>();
            }

            model.addAttribute("student", student);
            model.addAttribute("internships", internships);
            model.addAttribute("myApplications", myApplications);
            return "studentdashboard";
        } 
        // If user is a recruiter, redirect to recruiter dashboard
        else if (user instanceof Recruiter) {
            return "redirect:/recruiter/dashboard";
        }
        // If no user, redirect to signin
        else {
            return "redirect:/signin";
        }
    }

    @GetMapping("/recruiter/dashboard")
    public String recruiterDashboard(Model model, HttpSession session) {
        Object user = session.getAttribute("user");
        
        // Check if user is a recruiter
        if (user instanceof Recruiter) {
            Recruiter recruiter = (Recruiter) user;

            List<Internship> myInternships = internshipService.getInternshipsByRecruiter(recruiter.getId());
            if (myInternships == null) {
                myInternships = new ArrayList<>();
            }

            long totalApplications = myInternships.stream()
                    .mapToLong(internship -> {
                        if (internship.getAppliedStudentIds() != null) {
                            return internship.getAppliedStudentIds().size();
                        }
                        return 0L;
                    })
                    .sum();

            long activePosts = myInternships.stream()
                    .filter(internship -> internship.getDeadline() != null
                            && internship.getDeadline().isAfter(LocalDateTime.now()))
                    .count();

            model.addAttribute("recruiter", recruiter);
            model.addAttribute("myInternships", myInternships);
            model.addAttribute("totalApplications", totalApplications);
            model.addAttribute("activePosts", activePosts);

            return "recruiterdashboard";
        }
        // If user is a student, redirect to student dashboard
        else if (user instanceof Student) {
            return "redirect:/student/dashboard";
        }
        // If no user, redirect to signin
        else {
            return "redirect:/signin";
        }
    }

    @PostMapping("/apply/{internshipId}")
    public String applyForInternship(@PathVariable Long internshipId,
            @RequestParam("resume") MultipartFile resume,
            HttpSession session) {
        Object user = session.getAttribute("user");
        
        // Check if user is a student
        if (user instanceof Student) {
            Student student = (Student) user;

            if (resume.isEmpty()) {
                return "redirect:/student/dashboard?error=resume_required";
            }

            // Check file type
            String contentType = resume.getContentType();
            if (contentType == null ||
                    (!contentType.equals("application/pdf") &&
                            !contentType.equals("application/msword") &&
                            !contentType
                                    .equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))) {
                return "redirect:/student/dashboard?error=invalid_file_type";
            }

            // Check file size (max 10MB)
            if (resume.getSize() > 10 * 1024 * 1024) {
                return "redirect:/student/dashboard?error=file_too_large";
            }

            try {
                internshipService.applyForInternship(internshipId, student.getId(), resume);
                return "redirect:/student/dashboard?success=applied";
            } catch (Exception e) {
                return "redirect:/student/dashboard?error=application_failed";
            }
        } else {
            return "redirect:/signin";
        }
    }

    @PostMapping("/internship/{internshipId}/applicant/{studentId}/decision")
    public String updateApplicationDecision(@PathVariable Long internshipId,
            @PathVariable Long studentId,
            @RequestParam String decision,
            @RequestParam(required = false) String rejectionReason,
            HttpSession session) {
        Object user = session.getAttribute("user");
        
        // Check if user is a recruiter
        if (user instanceof Recruiter) {
            Recruiter recruiter = (Recruiter) user;

            // Verify the internship belongs to this recruiter
            Internship internship = internshipService.getInternshipById(internshipId);
            if (internship == null || internship.getRecruiter() == null ||
                    !internship.getRecruiter().getId().equals(recruiter.getId())) {
                return "redirect:/recruiter/dashboard";
            }

            // Validate decision
            if (!decision.equals("ACCEPTED") && !decision.equals("REJECTED")) {
                return "redirect:/internship/" + internshipId + "/applicants?error=invalid_decision";
            }

            try {
                internshipService.updateApplicationStatus(internshipId, studentId, decision, rejectionReason);

                // If accepted, add notification to student
                if ("ACCEPTED".equals(decision)) {
                    Student student = studentService.findById(studentId);
                    if (student != null) {
                        String notification = "Congratulations! Your application for '" + internship.getTitle()
                                + "' at " + internship.getRecruiter().getCompanyName() + " has been accepted!";
                        student.addNotification(notification);
                        studentService.saveStudent(student);
                    }
                }
                // If rejected with reason, add notification to student
                else if ("REJECTED".equals(decision) && rejectionReason != null && !rejectionReason.trim().isEmpty()) {
                    Student student = studentService.findById(studentId);
                    if (student != null) {
                        String notification = "Update on your application for '" + internship.getTitle()
                                + "': " + rejectionReason;
                        student.addNotification(notification);
                        studentService.saveStudent(student);
                    }
                }

                return "redirect:/internship/" + internshipId + "/applicants?success=decision_updated";
            } catch (Exception e) {
                return "redirect:/internship/" + internshipId + "/applicants?error=update_failed";
            }
        } else {
            return "redirect:/signin";
        }
    }

    @GetMapping("/internship/{id}/applicants")
    public String viewApplicants(@PathVariable Long id, Model model, HttpSession session) {
        Object user = session.getAttribute("user");
        
        // Check if user is a recruiter
        if (user instanceof Recruiter) {
            Recruiter recruiter = (Recruiter) user;

            Internship internship = internshipService.getInternshipById(id);
            if (internship == null || internship.getRecruiter() == null
                    || !internship.getRecruiter().getId().equals(recruiter.getId())) {
                return "redirect:/recruiter/dashboard";
            }

            List<Long> applicantIds = internshipService.getApplicantsForInternship(id);
            List<Student> applicants = studentService.getStudentsByIds(applicantIds);

            // Calculate statistics - SIMPLIFIED
            long totalApplicants = applicants.size();
            long pendingApplicants = totalApplicants; // Default all pending
            long acceptedApplicants = 0;
            long rejectedApplicants = 0;

            if (internship.getApplicationStatuses() != null) {
                acceptedApplicants = (int) internship.getApplicationStatuses().stream()
                        .filter(status -> "ACCEPTED".equals(status.getStatus()))
                        .count();
                rejectedApplicants = (int) internship.getApplicationStatuses().stream()
                        .filter(status -> "REJECTED".equals(status.getStatus()))
                        .count();
                pendingApplicants = totalApplicants - acceptedApplicants - rejectedApplicants;
            }

            model.addAttribute("internship", internship);
            model.addAttribute("applicants", applicants);
            model.addAttribute("totalApplicants", totalApplicants);
            model.addAttribute("pendingApplicants", pendingApplicants);
            model.addAttribute("acceptedApplicants", acceptedApplicants);
            model.addAttribute("rejectedApplicants", rejectedApplicants);

            return "applicants";
        } else {
            return "redirect:/signin";
        }
    }

    // Add method to handle notification viewing
    @GetMapping("/student/notifications")
    public String viewNotifications(Model model, HttpSession session) {
        Object user = session.getAttribute("user");
        
        // Check if user is a student
        if (user instanceof Student) {
            Student student = (Student) user;

            // Refresh student data
            Student freshStudent = studentService.findById(student.getId());
            if (freshStudent != null) {
                session.setAttribute("user", freshStudent);
                student = freshStudent;
            }

            model.addAttribute("student", student);
            return "studentdashboard"; // This will show the notifications modal
        } else {
            return "redirect:/signin";
        }
    }

    // Add method to clear notifications
    @PostMapping("/student/notifications/clear")
    public String clearNotifications(HttpSession session) {
        Object user = session.getAttribute("user");
        
        // Check if user is a student
        if (user instanceof Student) {
            Student student = (Student) user;

            Student freshStudent = studentService.findById(student.getId());
            if (freshStudent != null) {
                freshStudent.setNotifications(new java.util.ArrayList<>());
                studentService.saveStudent(freshStudent);
                session.setAttribute("user", freshStudent);
            }

            return "redirect:/student/dashboard?success=notifications_cleared";
        } else {
            return "redirect:/signin";
        }
    }
}