package com.dp_sys.controller;


import com.dp_sys.dto.Result;
import com.dp_sys.service.IFollowService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author DAHUANG
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

    @Autowired
    private IFollowService iFollowService;

    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable("id") Long followUserID,@PathVariable("isFollow") Boolean isFollow){
        return iFollowService.follow(followUserID,isFollow);
    }

    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id") Long followUserID){
        return iFollowService.isFollow(followUserID);
    }

    @GetMapping("/common/{id}")
    public Result commonFollow(@PathVariable("id") Long id){
        return iFollowService.commonFollow(id);
    }
}
