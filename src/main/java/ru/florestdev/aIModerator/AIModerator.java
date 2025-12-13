package ru.florestdev.aIModerator;

import com.google.gson.*;
import org.bukkit.Bukkit;
import org.bukkit.block.Sign;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

public class AIModerator extends JavaPlugin implements Listener {

    private String apiKey;
    private HttpClient httpClient;
    private final Gson gson = new Gson();

    static class Rule {
        String name;
        String description;
        double threshold;
        String action;
        String actionMessage;
        String command;

        Rule(String name, String description, double threshold,
             String action, String actionMessage, String command) {

            this.name = name;
            this.description = description;
            this.threshold = threshold;
            this.action = action;
            this.actionMessage = actionMessage;
            this.command = command;
        }
    }

    private final List<Rule> rules = new ArrayList<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadPluginConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("AIModerator загружен!");
    }

    private void reloadPluginConfig() {
        reloadConfig();
        apiKey = getConfig().getString("api_key");
        if (apiKey == null || apiKey.isEmpty()) {
            getLogger().severe("API KEY НЕ УКАЗАН!");
            return;
        }
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        loadRules();
    }

    private void loadRules() {
        rules.clear();
        ConfigurationSection sec = getConfig().getConfigurationSection("rules");
        if (sec == null) {
            getLogger().warning("rules: пусто в config.yml");
            return;
        }
        for (String key : sec.getKeys(false)) {
            String name = sec.getString(key + ".name");
            String description = sec.getString(key + ".description");
            double threshold = sec.getDouble(key + ".threshold");
            String action = sec.getString(key + ".action", "kick");
            String actionMessage = sec.getString(key + ".action_message", "Нарушение правила: %rule%");
            String command = sec.getString(key + ".command", "");
            rules.add(new Rule(name, description, threshold, action, actionMessage, command));
        }
        getLogger().info("Загружено правил: " + rules.size());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("aimreload")) {
            reloadPluginConfig();
            sender.sendMessage("§a[AIModerator] Конфиг перезагружен!");
            return true;
        }
        return false;
    }

    private void takeAction(Player p, Rule rule, AsyncPlayerChatEvent event) {
        Bukkit.getScheduler().runTask(this, () -> {
            String msg = rule.actionMessage.replace("%rule%", rule.name);
            switch (rule.action.toLowerCase()) {
                case "kick":
                    event.setCancelled(true);
                    p.kickPlayer(msg);
                    break;
                case "ban":
                    event.setCancelled(true);
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ban " + p.getName() + " " + msg);
                    break;
                case "warn":
                    event.setCancelled(true);
                    p.sendMessage("§cПредупреждение: §f" + msg);
                    break;
                case "command":
                    event.setCancelled(true);
                    if (rule.command != null && !rule.command.isEmpty()) {
                        String cmd = rule.command.replace("%player%", p.getName())
                                .replace("%rule%", rule.name);
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                    }
                    break;
                default:
                    p.sendMessage("§cОшибка: неизвестное действие " + rule.action);
            }
        });
    }


    private void analyzeMessage(Player p, String msg, AsyncPlayerChatEvent event) {
        try {
            Map<String, Object> requestJson = buildRequestMap(msg);
            String jsonBody = gson.toJson(requestJson);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.fireworks.ai/inference/v1/chat/completions"))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            Map<?, ?> json = gson.fromJson(response.body(), Map.class);

            List<?> choices = (List<?>) json.get("choices");
            if (choices == null || choices.isEmpty()) return;

            Map<?, ?> choice = (Map<?, ?>) choices.get(0);
            Map<?, ?> messageMap = (Map<?, ?>) choice.get("message");
            if (messageMap == null) return;

            String content = (String) messageMap.get("content");
            if (content == null || content.isEmpty()) return;

            Map<String, Double> analysis = gson.fromJson(content, Map.class);

            for (Rule r : rules) {
                if (!analysis.containsKey(r.name)) continue;

                double prob = analysis.get(r.name);
                getLogger().info("[AIMod] " + p.getName() + " rule=" + r.name + " prob=" + prob);

                if (prob >= r.threshold) {
                    takeAction(p, r, event);
                    return;
                }
            }

        } catch (Exception ex) {
            getLogger().warning("Ошибка анализа: " + ex.getMessage());
        }
    }


    // ====================== ЧАТ ======================
    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        String message = e.getMessage();
        Bukkit.getScheduler().runTaskAsynchronously(this, () ->
                analyzeMessage(e.getPlayer(), message, e)
        );
    }

    // ====================== КНИГИ ======================
    @EventHandler
    public void onBook(PlayerEditBookEvent e) {
        if (!e.isSigning()) return; // только подписываемые книги

        Player p = e.getPlayer();
        BookMeta meta = e.getNewBookMeta();
        List<String> originalPages = meta.getPages(); // сохраняем оригинальные страницы
        String fullText = String.join("\n", originalPages);

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            boolean violation = false;
            Rule violatedRule = null;

            try {
                Map<String, Object> requestJson = buildRequestMap(fullText);
                String jsonBody = gson.toJson(requestJson);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("https://api.fireworks.ai/inference/v1/chat/completions"))
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                Map<?, ?> json = gson.fromJson(response.body(), Map.class);
                List<?> choices = (List<?>) json.get("choices");

                if (choices != null && !choices.isEmpty()) {
                    Map<?, ?> choice = (Map<?, ?>) choices.get(0);
                    Map<?, ?> messageMap = (Map<?, ?>) choice.get("message");
                    if (messageMap != null) {
                        String content = (String) messageMap.get("content");
                        if (content != null && !content.isEmpty()) {
                            Map<String, Double> analysis = gson.fromJson(content, Map.class);
                            for (Rule r : rules) {
                                if (!analysis.containsKey(r.name)) continue;
                                double prob = analysis.get(r.name);
                                if (prob >= r.threshold) {
                                    violation = true;
                                    violatedRule = r;
                                    break;
                                }
                            }
                        }
                    }
                }
            } catch (Exception ex) {
                getLogger().warning("Ошибка анализа книги: " + ex.getMessage());
                violation = true; // блокируем при ошибке API
            }

            Rule finalViolatedRule = violatedRule;
            boolean finalViolation = violation;

            Bukkit.getScheduler().runTask(this, () -> {
                if (finalViolation && finalViolatedRule != null) {
                    // заменяем текст книги на запрещено
                    BookMeta bmeta = e.getNewBookMeta();
                    List<String> blockedPages = new ArrayList<>();
                    for (String page : originalPages) blockedPages.add("§cЗапрещено");
                    bmeta.setPages(blockedPages);
                    e.setNewBookMeta(bmeta);

                    takeAction(p, finalViolatedRule, e); // предупреждение/кик/бан/команда
                }
                // если нарушения нет — ничего не делаем, книга подписывается нормально
            });
        });
    }



    // Универсальный takeAction для книг
    private void takeAction(Player p, Rule rule, PlayerEditBookEvent event) {
        Bukkit.getScheduler().runTask(this, () -> {
            String msg = rule.actionMessage.replace("%rule%", rule.name);
            switch (rule.action.toLowerCase()) {
                case "kick" -> p.kickPlayer(msg);
                case "ban" -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ban " + p.getName() + " " + msg);
                case "warn" -> p.sendMessage("§cПредупреждение: §f" + msg);
                case "command" -> {
                    if (rule.command != null && !rule.command.isEmpty()) {
                        String cmd = rule.command.replace("%player%", p.getName())
                                .replace("%rule%", rule.name);
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                    }
                }
                default -> p.sendMessage("§cОшибка: неизвестное действие " + rule.action);
            }
            event.setCancelled(true); // блокируем книгу при нарушении
        });
    }

    // ====================== ТАБЛИЧКИ ======================
    @EventHandler
    public void onSign(SignChangeEvent e) {
        Player p = e.getPlayer();
        String[] original = e.getLines().clone(); // сохраняем исходный текст
        String text = String.join("\n", original);

        // отменяем событие, пока проверяем текст
        for (int i = 0; i < original.length; i++) {
            e.setLine(i, "§cANALYZING...");
        }

        // асинхронная проверка
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            Rule violationRule = null;

            try {
                Map<String, Object> requestJson = buildRequestMap(text);
                String jsonBody = gson.toJson(requestJson);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("https://api.fireworks.ai/inference/v1/chat/completions"))
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                Map<?, ?> json = gson.fromJson(response.body(), Map.class);

                List<?> choices = (List<?>) json.get("choices");
                if (choices != null && !choices.isEmpty()) {
                    Map<?, ?> choice = (Map<?, ?>) choices.get(0);
                    Map<?, ?> messageMap = (Map<?, ?>) choice.get("message");
                    if (messageMap != null) {
                        String content = (String) messageMap.get("content");
                        if (content != null && !content.isEmpty()) {
                            Map<String, Double> analysis = gson.fromJson(content, Map.class);
                            for (Rule r : rules) {
                                if (!analysis.containsKey(r.name)) continue;
                                double prob = analysis.get(r.name);
                                getLogger().info("[AIMod][Sign] " + p.getName() + " rule=" + r.name + " prob=" + prob);
                                if (prob >= r.threshold) {
                                    violationRule = r;
                                    break;
                                }
                            }
                        }
                    }
                }
            } catch (Exception ex) {
                getLogger().warning("Ошибка анализа таблички: " + ex.getMessage());
                violationRule = rules.isEmpty() ? null : rules.get(0); // при ошибке блокируем
            }

            Rule finalViolationRule = violationRule;

            // возвращаем в главный поток
            Bukkit.getScheduler().runTask(this, () -> {
                Sign sign = (Sign) e.getBlock().getState();

                if (finalViolationRule != null) {
                    // используем универсальный takeAction
                    for (int i = 0; i < original.length; i++) {
                        e.setLine(i, "§cBREAKING RULES.");
                    }
                    takeActionForSign(p, finalViolationRule, e);
                } else {
                    // возвращаем оригинальный текст
                    for (int i = 0; i < original.length; i++)
                        sign.setLine(i, original[i]);
                    sign.update();
                }
            });
        });
    }




    private void takeActionForSign(Player p, Rule rule, SignChangeEvent event) {
        Bukkit.getScheduler().runTask(this, () -> {
            String msg = rule.actionMessage.replace("%rule%", rule.name);
            switch (rule.action.toLowerCase()) {
                case "kick" -> p.kickPlayer(msg);
                case "ban" -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ban " + p.getName() + " " + msg);
                case "warn" -> p.sendMessage("§cПредупреждение: §f" + msg);
                case "command" -> {
                    if (rule.command != null && !rule.command.isEmpty()) {
                        String cmd = rule.command.replace("%player%", p.getName())
                                .replace("%rule%", rule.name);
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                    }
                }
                default -> p.sendMessage("§cОшибка: неизвестное действие " + rule.action);
            }
        });
    }


    // ====================== ВСПОМОГАТЕЛЬНЫЕ ======================
    private Map<String, Object> buildRequestMap(String message) {
        Map<String, Object> request = new HashMap<>();
        request.put("model", "accounts/fireworks/models/deepseek-v3p2");
        request.put("max_tokens", 1024);
        request.put("temperature", 0.0);

        List<Map<String, String>> messages = new ArrayList<>();
        Map<String, String> systemMsg = new HashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", "Ты — система модерации. Верни JSON вида {\"RuleName\": probability} для правил:\n" + rulesToString());
        messages.add(systemMsg);

        Map<String, String> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", message);
        messages.add(userMsg);

        request.put("messages", messages);
        return request;
    }

    private String rulesToString() {
        StringBuilder sb = new StringBuilder();
        for (Rule r : rules) sb.append("- ").append(r.name).append(": ").append(r.description).append("\n");
        return sb.toString();
    }
}
