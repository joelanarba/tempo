package com.tempoapi.modules.users;

import com.tempoapi.common.ApiResponse;
import com.tempoapi.modules.users.dto.UserProfileResponse;
import com.tempoapi.security.SecurityUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserProfileResponse>> me() {
        UserProfileResponse profile = userService.getProfile(SecurityUtils.currentUserId());
        return ResponseEntity.ok(ApiResponse.ok(profile));
    }
}
