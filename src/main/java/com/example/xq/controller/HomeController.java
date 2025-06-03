package com.example.xq.controller;

import cn.hutool.core.io.FileUtil;
import com.example.xq.MainService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.File;
import java.io.IOException;

@Controller
public class HomeController {

    @Resource
    private MainService mainService;

    @RequestMapping
    public String index() throws IOException {

        return "index";
    }

    @PostMapping("upload")
    public String upload(MultipartFile file, RedirectAttributes atts) throws IOException, InterruptedException {

        String originalFilename = file.getOriginalFilename();
        File tempFile = FileUtil.createTempFile(FileUtil.extName(originalFilename), true);
        file.transferTo(tempFile);

        String action = mainService.process(tempFile.getAbsolutePath());

        tempFile.delete();

        atts.addFlashAttribute("action",action);
        return "redirect:/";
    }
}
