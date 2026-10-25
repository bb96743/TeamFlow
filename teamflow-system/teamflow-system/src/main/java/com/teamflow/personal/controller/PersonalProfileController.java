package com.teamflow.personal.controller;

import com.teamflow.common.domain.R;
import com.teamflow.framework.security.RequiresPermission;
import com.teamflow.personal.service.PersonalProfileService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * M10 个人效能画像接口（{@code GET /api/personal/profile}），需求 2.3.5。
 *
 * <p>R7：只读、实时聚合、**不落明细画像表**；只返回当前登录用户本人的数据。</p>
 */
@RestController
@RequestMapping("/api/personal/profile")
public class PersonalProfileController {

    private final PersonalProfileService profileService;

    public PersonalProfileController(PersonalProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping
    @RequiresPermission("personal:profile:view")
    public R<Map<String, Object>> profile() {
        return R.success(profileService.profile());
    }
}
