package com.seatflow.user.service;

import com.seatflow.common.domain.dto.PagedResult;
import com.seatflow.user.web.dto.request.UpdateUserProfileRequest;
import com.seatflow.user.web.dto.response.UserProfileResponse;
import org.springframework.data.domain.Pageable;

public interface UserService {

    /**
     * Retrieve the current user's profile. If the user does not exist in the database
     * (first authenticated request), perform JIT provisioning: create the user profile
     * from JWT claims and atomically write an EventEnvelope<UserRegisteredEvent> to outbox_events.
     *
     * @param externalId The JWT 'sub' claim (external identity provider subject ID)
     * @param email      The JWT 'email' claim
     * @return UserProfileResponse
     */
    UserProfileResponse getOrCreateUserProfile(String externalId, String email);

    /**
     * Update the current user's profile (phone).
     * If the user does not exist, perform JIT provisioning first.
     *
     * @param externalId The JWT 'sub' claim
     * @param email      The JWT 'email' claim
     * @param request    UpdateUserProfileRequest with new profile fields
     * @return Updated UserProfileResponse
     */
    UserProfileResponse updateUserProfile(String externalId, String email, UpdateUserProfileRequest request);

    /**
     * Admin-only: List all registered users with pagination.
     *
     * @param pageable Spring Data Pageable (page, size, sort)
     * @return PagedResult<UserProfileResponse>
     */
    PagedResult<UserProfileResponse> getAllUsers(Pageable pageable);
}
