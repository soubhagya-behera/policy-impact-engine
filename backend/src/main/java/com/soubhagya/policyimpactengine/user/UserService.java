package com.soubhagya.policyimpactengine.user;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

/**
 * Phase 2P — minimal user creation (auth-deferred).
 */
@Service
public class UserService {

	private final UserRepository userRepository;

	public UserService(UserRepository userRepository) {
		if (userRepository == null) {
			throw new IllegalArgumentException("UserRepository must not be null");
		}
		this.userRepository = userRepository;
	}

	@Transactional
	public User createUser() {
		return userRepository.saveAndFlush(new User());
	}

	@Transactional(readOnly = true)
	public User getUser(UUID userId) {
		if (userId == null) {
			throw new IllegalArgumentException("User id must not be null");
		}
		return userRepository.findById(userId)
				.orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
	}
}
