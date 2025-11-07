// MessageController.java - FIXED VERSION
package com.internshipfinder.zirah.controller;

import com.internshipfinder.zirah.model.*;
import com.internshipfinder.zirah.service.MessageService;
import com.internshipfinder.zirah.service.StudentService;
import com.internshipfinder.zirah.service.RecruiterService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Controller
@RequestMapping("/messages")
public class MessageController {

    @Autowired
    private MessageService messageService;

    @Autowired
    private StudentService studentService;

    @Autowired
    private RecruiterService recruiterService;

    // Main messages page - FIXED
    @GetMapping
    public String showMessages(HttpSession session, Model model) {
        try {
            Object user = session.getAttribute("user");
            if (user == null) {
                return "redirect:/signin";
            }

            UserType userType = getUserType(user);
            Long userId = getUserId(user);
            
            if (userId == null) {
                return "redirect:/signin";
            }

            List<MessageService.MessageThreadDTO> threads = 
                messageService.getUserThreadsWithPreview(userId, userType);
            
            model.addAttribute("threads", threads != null ? threads : List.of());
            model.addAttribute("userType", userType.name());
            return "messages";
        } catch (Exception e) {
            e.printStackTrace();
            model.addAttribute("error", "Failed to load messages");
            return "messages";
        }
    }

    // Compose message page - FIXED
    @GetMapping("/compose")
    public String showComposeForm(@RequestParam(required = false) Long toUserId,
                                @RequestParam(required = false) String toUserType,
                                HttpSession session, 
                                Model model) {
        try {
            Object user = session.getAttribute("user");
            if (user == null) {
                return "redirect:/signin";
            }

            // Safe handling of parameters
            model.addAttribute("toUserId", toUserId);
            model.addAttribute("toUserType", toUserType);
            
            // Get user name safely
            if (toUserId != null && toUserId > 0 && toUserType != null && !toUserType.trim().isEmpty()) {
                try {
                    UserType userTypeEnum = UserType.valueOf(toUserType.toUpperCase());
                    String userName = messageService.getUserName(toUserId, userTypeEnum);
                    model.addAttribute("toUserName", userName != null ? userName : "User");
                } catch (IllegalArgumentException e) {
                    model.addAttribute("toUserName", "User");
                }
            } else {
                model.addAttribute("toUserName", "User");
            }

            return "compose-message";
        } catch (Exception e) {
            e.printStackTrace();
            model.addAttribute("error", "Failed to load compose page");
            return "compose-message";
        }
    }

    // Send message - FIXED
    @PostMapping("/send")
    public String sendMessage(@RequestParam(required = false) Long receiverId,
                            @RequestParam(required = false) String receiverType,
                            @RequestParam(required = false) String content,
                            HttpSession session) {
        
        try {
            Object user = session.getAttribute("user");
            if (user == null) {
                return "redirect:/signin";
            }

            // Validate input
            if (content == null || content.trim().isEmpty()) {
                return "redirect:/messages/compose?error=empty_message";
            }

            if (receiverId == null || receiverType == null) {
                return "redirect:/messages/compose?error=missing_receiver";
            }

            UserType senderType = getUserType(user);
            Long senderId = getUserId(user);

            UserType receiverUserType;
            try {
                receiverUserType = UserType.valueOf(receiverType.toUpperCase());
            } catch (IllegalArgumentException e) {
                return "redirect:/messages/compose?error=invalid_receiver_type";
            }

            Message message = new Message();
            message.setSenderId(senderId);
            message.setSenderType(senderType);
            message.setReceiverId(receiverId);
            message.setReceiverType(receiverUserType);
            message.setContent(content.trim());

            messageService.sendMessage(message);

            return "redirect:/messages/thread/" + receiverId + "/" + receiverType.toUpperCase() + "?success=true";

        } catch (Exception e) {
            e.printStackTrace();
            return "redirect:/messages/compose?error=send_failed";
        }
    }

    // View conversation thread - FIXED
    @GetMapping("/thread/{otherUserId}/{otherUserType}")
    public String showThread(@PathVariable(required = false) Long otherUserId, 
                           @PathVariable(required = false) String otherUserType,
                           HttpSession session, 
                           Model model) {
        try {
            // Validate parameters
            if (otherUserId == null || otherUserType == null) {
                return "redirect:/messages?error=invalid_parameters";
            }

            Object user = session.getAttribute("user");
            if (user == null) {
                return "redirect:/signin";
            }

            UserType currentUserType = getUserType(user);
            Long currentUserId = getUserId(user);
            
            UserType otherType;
            try {
                otherType = UserType.valueOf(otherUserType.toUpperCase());
            } catch (IllegalArgumentException e) {
                return "redirect:/messages?error=invalid_user_type";
            }

            List<Message> conversation = messageService.getConversation(
                currentUserId, currentUserType, otherUserId, otherType);
            
            String otherUserName = messageService.getUserName(otherUserId, otherType);
            
            model.addAttribute("conversation", conversation != null ? conversation : List.of());
            model.addAttribute("otherUserId", otherUserId);
            model.addAttribute("otherUserType", otherUserType.toUpperCase());
            model.addAttribute("otherUserName", otherUserName != null ? otherUserName : "User");
            model.addAttribute("currentUserId", currentUserId);
            model.addAttribute("currentUserType", currentUserType.name());

            return "message-thread";
        } catch (Exception e) {
            e.printStackTrace();
            model.addAttribute("error", "Failed to load conversation");
            return "message-thread";
        }
    }

    // Real-time message polling endpoint - FIXED
    @GetMapping("/poll")
    @ResponseBody
    public DeferredResult<ResponseEntity<?>> pollForNewMessages(
            @RequestParam(required = false) Long otherUserId,
            @RequestParam(required = false) String otherUserType,
            @RequestParam(required = false) Long lastMessageId,
            HttpSession session) {
        
        DeferredResult<ResponseEntity<?>> deferredResult = new DeferredResult<>(30000L);
        
        try {
            Object user = session.getAttribute("user");
            if (user == null) {
                deferredResult.setResult(ResponseEntity.status(401).build());
                return deferredResult;
            }

            // Validate parameters
            if (otherUserId == null || otherUserType == null) {
                deferredResult.setResult(ResponseEntity.badRequest().build());
                return deferredResult;
            }

            UserType currentUserType = getUserType(user);
            Long currentUserId = getUserId(user);
            
            UserType otherType = UserType.valueOf(otherUserType.toUpperCase());
            
            // Use async processing
            CompletableFuture.runAsync(() -> {
                try {
                    // Wait for new messages
                    Thread.sleep(1000); // Simulate waiting
                    deferredResult.setResult(ResponseEntity.noContent().build());
                    
                } catch (Exception e) {
                    deferredResult.setResult(ResponseEntity.status(500).build());
                }
            });
            
        } catch (Exception e) {
            deferredResult.setResult(ResponseEntity.status(500).build());
        }
        
        return deferredResult;
    }

    // Helper methods with NULL CHECKS
    private UserType getUserType(Object user) {
        if (user instanceof Student) {
            return UserType.STUDENT;
        } else if (user instanceof Recruiter) {
            return UserType.RECRUITER;
        }
        throw new IllegalArgumentException("Unknown user type: " + (user != null ? user.getClass().getSimpleName() : "null"));
    }

    private Long getUserId(Object user) {
        if (user instanceof Student) {
            return ((Student) user).getId();
        } else if (user instanceof Recruiter) {
            return ((Recruiter) user).getId();
        }
        throw new IllegalArgumentException("Cannot get ID from null user");
    }
}