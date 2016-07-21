package io.sponges.bot.client.skype;

import com.samczsun.skype4j.Skype;
import com.samczsun.skype4j.SkypeBuilder;
import com.samczsun.skype4j.Visibility;
import com.samczsun.skype4j.chat.Chat;
import com.samczsun.skype4j.chat.GroupChat;
import com.samczsun.skype4j.chat.IndividualChat;
import com.samczsun.skype4j.chat.messages.ReceivedMessage;
import com.samczsun.skype4j.events.EventHandler;
import com.samczsun.skype4j.events.Listener;
import com.samczsun.skype4j.events.chat.message.MessageReceivedEvent;
import com.samczsun.skype4j.events.chat.user.MultiUserAddEvent;
import com.samczsun.skype4j.events.chat.user.UserAddEvent;
import com.samczsun.skype4j.events.chat.user.UserRemoveEvent;
import com.samczsun.skype4j.events.chat.user.action.TopicUpdateEvent;
import com.samczsun.skype4j.events.contact.ContactRequestEvent;
import com.samczsun.skype4j.exceptions.ChatNotFoundException;
import com.samczsun.skype4j.exceptions.ConnectionException;
import com.samczsun.skype4j.exceptions.InvalidCredentialsException;
import com.samczsun.skype4j.exceptions.NotParticipatingException;
import com.samczsun.skype4j.formatting.Message;
import com.samczsun.skype4j.formatting.Text;
import com.samczsun.skype4j.user.Contact;
import com.samczsun.skype4j.user.User;
import io.sponges.bot.client.Bot;
import io.sponges.bot.client.event.events.*;
import io.sponges.bot.client.protocol.msg.ChannelDataUpdateMessage;
import io.sponges.bot.client.protocol.msg.ChatMessage;
import io.sponges.bot.client.protocol.msg.UserJoinMessage;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class Client implements Listener {

    private static final long RELOG_DELAY = (1000 * 60) * 60; // 60 mins

    private final AtomicInteger runs = new AtomicInteger(0);

    private final AtomicReference<Skype> skype = new AtomicReference<>();
    private final Bot bot;

    private String[] credentials;

    public Client() throws ConnectionException, NotParticipatingException, InvalidCredentialsException {
        this.credentials = loadCredentials(new File("credentials.json"));
        assert credentials != null;
        this.skype.set(login(credentials[0], credentials[1]));
        Timer timer = new Timer();
        this.bot = new Bot("skype", "-", "localhost", 9574);
        this.bot.getEventBus().register(CommandResponseEvent.class, event -> {
            Skype skype = this.skype.get();
            String network = event.getNetwork();
            Chat chat = skype.getChat(network);
            String response = event.getMessage();
            io.sponges.bot.client.cache.User user = event.getUser();
            String username = user.getId();
            try {
                chat.sendMessage(Message.create()
                        .with(Text.plain("(" + username + ") ").asRichText().withBold())
                        .with(Text.plain(response)));
            } catch (ConnectionException e) {
                e.printStackTrace();
            }
        });
        this.bot.getEventBus().register(StopEvent.class, event -> {
            timer.cancel();
            try {
                skype.get().logout();
            } catch (ConnectionException e) {
                e.printStackTrace();
            }
            System.exit(-1);
        });
        this.bot.getEventBus().register(SendRawEvent.class, event -> {
            Skype skype = this.skype.get();
            String network = event.getNetwork();
            Chat chat = skype.getChat(network);
            String response = event.getMessage();
            System.out.println("Requested to send " + response + "!");
            try {
                chat.sendMessage(response);
            } catch (ConnectionException e) {
                e.printStackTrace();
            }
        });
        this.bot.getEventBus().register(KickUserEvent.class, event -> {
            Skype skype = this.skype.get();
            String network = event.getNetwork();
            String username = event.getUser().getId();
            Chat chat;
            try {
                chat = skype.getOrLoadChat(network);
            } catch (ConnectionException | ChatNotFoundException e) {
                e.printStackTrace();
                return;
            }
            if (!(chat instanceof GroupChat)) {
                System.out.println("must be group chat instead of " + chat.getClass().getName());
                return;
            }
            GroupChat groupChat = (GroupChat) chat;
            try {
                groupChat.kick(username);
            } catch (ConnectionException e) {
                e.printStackTrace();
                try {
                    groupChat.sendMessage("Could not kick \"" + username + "\"! Does the bot have admin?");
                } catch (ConnectionException e1) {
                    e1.printStackTrace();
                }
            }
        });
        this.bot.getEventBus().register(UpdateChannelDataEvent.class, event -> {
            switch (event.getDetail()) {
                case NAME: {
                    Skype skype = this.skype.get();
                    String network = event.getNetwork();
                    Chat chat = skype.getChat(network);
                    try {
                        ((GroupChat) chat).setTopic(event.getValue());
                    } catch (ConnectionException e) {
                        e.printStackTrace();
                        try {
                            chat.sendMessage("Could not set group topic to  \"" + event.getValue() + "\": " + e.getMessage());
                        } catch (ConnectionException e1) {
                            e1.printStackTrace();
                        }
                    }
                    break;
                }
                case TOPIC: {
                    System.err.println("Unsupported operation change channel topic detail.");
                    break;
                }
            }
        });
        this.bot.getEventBus().register(ChannelMessageReceiveEvent.class, event -> {
            event.getId();
            String[] args = event.getMessage().split(" ");
            switch (args[0].toLowerCase()) {
                case "test":
                    event.reply(bot, "Reply for test message.");
                    break;
                case "list_users": {
                    String network = args[1];
                    Skype skype = this.skype.get();
                    Chat chat;
                    try {
                        chat = skype.getOrLoadChat(network);
                    } catch (ConnectionException e) {
                        e.printStackTrace();
                        break;
                    } catch (ChatNotFoundException e) {
                        event.reply(bot, "Invalid network");
                        break;
                    }
                    while (!chat.isLoaded()) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                    Collection<User> users = chat.getAllUsers();
                    JSONArray array = new JSONArray();
                    users.forEach(user -> array.put(user.getUsername()));
                    event.reply(bot, array.toString());
                    break;
                }
            }
        });
        this.bot.getEventBus().register(ResourceRequestEvent.class, event -> {
            String networkId = event.getNetworkId();
            Skype skype = this.skype.get();
            Chat chat;
            try {
                chat = skype.getOrLoadChat(networkId);
            } catch (ConnectionException e) {
                e.printStackTrace();
                event.replyInvalid(bot);
                return;
            } catch (ChatNotFoundException e) {
                event.replyInvalid(bot);
                return;
            }
            Map<String, String> parameters = new HashMap<>();
            switch (event.getType()) {
                case NETWORK: {
                    String name;
                    if (chat instanceof GroupChat) {
                        GroupChat groupChat = (GroupChat) chat;
                        name = groupChat.getTopic();
                    } else {
                        IndividualChat individualChat = (IndividualChat) chat;
                        name = individualChat.getPartner().getUsername();
                    }
                    parameters.put("name", name);
                    event.reply(bot, parameters);
                    break;
                }
                case CHANNEL: {
                    parameters.put("id", "skype-dummy-channel");
                    parameters.put("private", String.valueOf(chat instanceof IndividualChat));
                    event.reply(bot, parameters);
                    break;
                }
                case USER: {
                    User user = chat.getUser(event.getUserId());
                    if (user == null) {
                        event.replyInvalid(bot);
                        break;
                    }
                    parameters.put("id", user.getUsername());
                    String username = user.getUsername();
                    parameters.put("username", username);
                    boolean admin = user.getRole() == User.Role.ADMIN;
                    parameters.put("admin", String.valueOf(admin));
                    boolean op = username.equals("joepwnsall1");
                    parameters.put("op", String.valueOf(op));
                    String displayName = null;
                    try {
                        displayName = user.getDisplayName();
                    } catch (ConnectionException e) {
                        e.printStackTrace();
                    }
                    if (displayName != null) parameters.put("display-name", displayName);
                    Contact contact = null;
                    try {
                        contact = user.getContact();
                    } catch (ConnectionException e) {
                        e.printStackTrace();
                    }
                    if (contact != null) {
                        String mood = contact.getMood();
                        if (mood != null) parameters.put("mood", mood);
                        String profileImage = contact.getAvatarURL();
                        if (profileImage != null) parameters.put("profile-image", profileImage);
                        Chat privateChat = null;
                        try {
                            privateChat = contact.getPrivateConversation();
                        } catch (ConnectionException | ChatNotFoundException e) {
                            e.printStackTrace();
                        }
                        if (privateChat != null) {
                            parameters.put("private-network", privateChat.getIdentity());
                            parameters.put("private-channel", "skype-dummy-channel");
                        }
                    }
                    event.reply(bot, parameters);
                    break;
                }
            }
        });

        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                swap(null);
            }
        }, RELOG_DELAY, RELOG_DELAY);

        this.bot.start();
    }

    public static void main(String[] args) {
        try {
            new Client();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void swap(Chat chat) {
        new Thread(() -> {
            System.out.println(String.format("Attempting relog %s!", runs.incrementAndGet()));
            Skype newInstance;
            try {
                newInstance = login(credentials[0], credentials[1]);
            } catch (ConnectionException | NotParticipatingException | InvalidCredentialsException e) {
                e.printStackTrace();
                return;
            }
            if (newInstance == null) {
                System.out.println("New skype instance is null! Will try again next time :3");
                return;
            }
            Skype oldInstance = skype.getAndSet(newInstance);
            try {
                oldInstance.logout();
            } catch (ConnectionException e) {
                e.printStackTrace();
            }
            System.out.println("Swapped and logged out :)");
            if (chat != null) {
                try {
                    chat.sendMessage("Reloaded!");
                } catch (ConnectionException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    private Skype login(String username, String password) throws ConnectionException, NotParticipatingException, InvalidCredentialsException {
        System.out.println("Logging in...");
        Skype skype = new SkypeBuilder(username, password).withExceptionHandler((errorSource, throwable, b) -> {
            if (!throwable.getMessage().contains("While submitting active (404 Not Found)")) {
                System.out.println("Error! " + errorSource + " " + throwable.getClass() + " " + throwable.getMessage() + " " + b);
                throwable.printStackTrace();
            }
        }).withAllResources().build();
        skype.login();
        skype.getEventDispatcher().registerListener(this);
        skype.subscribe();
        skype.setVisibility(Visibility.ONLINE);
        System.out.println("Logged in, subscribed & onlined!");
        return skype;
    }

    @EventHandler
    public void onMessage(MessageReceivedEvent event) {
        ReceivedMessage message = event.getMessage();
        Chat chat = message.getChat();
        String networkId = chat.getIdentity();
        User user = message.getSender();
        String userId = user.getUsername();
        String username = user.getUsername();
        if (username.equals("spongy.bot") || username.equals("spongybot") || username.equals("ronniepickeringscar")) return;
        long time = message.getSentTime();
        String content = message.getContent().asPlaintext();
        if (content.equalsIgnoreCase("-spongybot unfuck") && username.equals("joepwnsall1")) {
            swap(chat);
            return;
        }
        ChatMessage chatMessage = new ChatMessage(bot, networkId, "skype-dummy-channel", userId, time, content);
        bot.getClient().sendMessage(chatMessage.toString());
    }

    @EventHandler
    public void onAdd(UserAddEvent event) {
        if (!(event.getChat() instanceof GroupChat)) return;
        GroupChat chat = (GroupChat) event.getChat();
        String networkId = chat.getIdentity();
        String channelId = "skype-dummy-channel";
        User added = event.getUser();
        String addedUserId = added.getUsername();
        String addedUsername = added.getUsername();
        if (addedUsername.equals("spongy.bot") || addedUsername.equals("spongybot") || addedUsername.equals("ronniepickeringscar")) return;
        String initiatorUserId = null;
        if (event.getInitiator() != null) {
            initiatorUserId = event.getInitiator().getUsername();
        }
        UserJoinMessage message = new UserJoinMessage(bot, networkId, channelId, addedUserId, initiatorUserId);
        bot.getClient().sendMessage(message.toString());
    }

    @EventHandler
    public void onMultiAdd(MultiUserAddEvent event) {
        for (int i = 1; i < event.getAllUsers().size(); i++) {
            User user = event.getAllUsers().get(i);
            UserAddEvent event1 = new UserAddEvent(user, event.getInitiator());
            onAdd(event1);
        }
    }

    @EventHandler
    public void onRemove(UserRemoveEvent event) throws ConnectionException {
        String kicked = event.getUser().getUsername();
        String kicker = event.getInitiator().getUsername();
        String chat = event.getChat().getIdentity();
        System.out.printf("%s was kicked from %s by %s!\n", kicked, chat, kicker);
    }

    @EventHandler
    public void onContact(ContactRequestEvent event) {
        try {
            System.out.println("Adding " + event.getRequest().getSender().getUsername());
            event.getRequest().accept();
            try {
                event.getRequest().getSender().getPrivateConversation().sendMessage("" +
                        "Hi, I'm SpongyBot! Sorry if it took a long time to accept your contact request, I'm a stupid robot :(" +
                        "\nTo see my commands, use -commands" +
                        "\nTo learn about me, use -about" +
                        "\nI work best in group chats, so feel free to make a new group chat or add me to one!" +
                        "\nWebsite: http://bot.sponges.io - this shows how to use the bot, some of the amazing features and if there are any issues with the bot." +
                        "\nBe sure to join the official Skype chat: https://join.skype.com/rxdbRgV0V1IV");
            } catch (ChatNotFoundException e) {
                e.printStackTrace();
            }
        } catch (ConnectionException e) {
            e.printStackTrace();
        }
    }

    @EventHandler
    public void onTopicUpdate(TopicUpdateEvent event) {
        if (!(event.getChat() instanceof GroupChat)) return;
        GroupChat groupChat = (GroupChat) event.getChat();
        String networkId = groupChat.getIdentity();
        String channelId = "skype-dummy-channel";
        User user = event.getUser();
        String userId = user.getUsername();
        if (userId.equals("spongy.bot") || userId.equalsIgnoreCase("spongybot")
                || userId.equalsIgnoreCase("ronniepickeringscar")) return;
        String oldTopic = event.getOldTopic() != null ? Jsoup.parse(event.getOldTopic()).text() : "null";
        String newTopic = event.getNewTopic() != null ? Jsoup.parse(event.getNewTopic()).text() : "null";
        UpdateChannelDataEvent.Detail detail = UpdateChannelDataEvent.Detail.NAME;
        ChannelDataUpdateMessage message = new ChannelDataUpdateMessage(bot, networkId, channelId, userId, detail,
                oldTopic, newTopic);
        bot.getClient().sendMessage(message.toString());
    }

    private String[] loadCredentials(File file) {
        if (!file.exists()) {
            BufferedWriter writer = null;
            try {
                writer = new BufferedWriter(new FileWriter(file));
                writer.write(new JSONObject().put("username", "").put("password", "").toString());
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                try {
                    if (writer != null) {
                        writer.close();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            return null;
        }
        BufferedReader reader = null;
        StringBuilder builder = new StringBuilder();
        try {
            reader = new BufferedReader(new FileReader(file));

            String input;
            while ((input = reader.readLine()) != null) {
                builder.append("\n").append(input);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        JSONObject json = new JSONObject(builder.toString());
        String username = json.getString("username");
        String password = json.getString("password");
        return new String[] { username, password };
    }

}
