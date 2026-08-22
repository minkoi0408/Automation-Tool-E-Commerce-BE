package com.example.toolecommerrce.config;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

@Configuration
public class SeleniumConfig {

    @Value("${scraper.shopee.headless:true}")
    private boolean headless;

    @Value("${scraper.shopee.implicit-wait-seconds:10}")
    private int implicitWaitSeconds;

    /**
     * ChromeOptions dùng chung cho mọi WebDriver instance.
     * Bean này prototype để mỗi lần gọi tạo 1 driver mới độc lập.
     */
    @Bean
    @Scope("prototype")
    public ChromeDriver chromeDriver() {
        WebDriverManager.chromedriver().setup();
        ChromeOptions options = buildChromeOptions();
        return new ChromeDriver(options);
    }

    private ChromeOptions buildChromeOptions() {
        ChromeOptions options = new ChromeOptions();

        if (headless) {
            options.addArguments("--headless=new");
        }

        // Giả lập trình duyệt thật để tránh anti-bot
        options.addArguments(
                "--disable-blink-features=AutomationControlled",
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--disable-gpu",
                "--window-size=1920,1080",
                "--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/120.0.0.0 Safari/537.36"
        );

        // Tắt tính năng automation detection
        options.setExperimentalOption("excludeSwitches", new String[]{"enable-automation"});
        options.setExperimentalOption("useAutomationExtension", false);

        // Tắt load hình ảnh để tăng tốc (bỏ comment nếu không cần ảnh từ page)
        // options.addArguments("--blink-settings=imagesEnabled=false");

        return options;
    }
}
