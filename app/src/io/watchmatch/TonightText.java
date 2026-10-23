package io.watchmatch;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every line Spark Tonight says or shows, in Portuguese, English and Spanish (the Arabic is in Arabic.java, keyed by the
 * English). `{spark}` is filled in by Ui.brand. Pure, so the checks can ask every line for its Arabic.
 */
final class TonightText {
    private TonightText() {}

    /** Ui::tr on the TV, anything in a check. */
    interface Tr {
        String tr(String pt, String en, String es);
    }

    private static final Map<String, String[]> LINES = new LinkedHashMap<>();

    private static void add(String id, String pt, String en, String es) {
        LINES.put(id, new String[] {pt, en, es});
    }

    static {
        add("title", "Pergunte ao {spark}", "Ask {spark}", "Pregunta a {spark}");
        add("lead", "Responda umas perguntas rápidas e receba títulos escolhidos para você.",
                "Answer a few quick questions and get titles picked for you.",
                "Responde unas preguntas rápidas y recibe títulos elegidos para ti.");
        add("hint", "Escreva ou diga sua resposta…", "Type or say your answer…", "Escribe o di tu respuesta…");
        add("listening", "Ouvindo…", "Listening…", "Escuchando…");
        add("thinking", "Pensando…", "Thinking…", "Pensando…");
        add("finding", "Procurando títulos…", "Finding titles…", "Buscando títulos…");
        add("picks", "Escolhidos para você", "Picked for you", "Elegidos para ti");
        add("nothing", "Não encontrei nada. Tente outras respostas.", "I could not find a match. Try other answers.",
                "No encontré nada. Prueba otras respuestas.");
        add("pickOne", "Escolha uma das opções.", "Please pick one of the options.", "Elige una de las opciones.");
        add("fallbackNote", "O chat inteligente não está disponível agora. Vamos com umas perguntas rápidas.",
                "The smart chat is not available right now, so here are a few quick questions.",
                "El chat inteligente no está disponible ahora, así que van unas preguntas rápidas.");
        add("leadIn", "Isto combina com você.", "Here is what fits you.", "Esto encaja contigo.");
        add("capped", "Chega de perguntas. Aqui está o que tenho.", "That is enough questions. Here is what I have.",
                "Ya basta de preguntas. Esto es lo que tengo.");
        add("startOver", "Recomeçar", "Start over", "Empezar de nuevo");
        add("send", "Enviar", "Send", "Enviar");
        add("voice", "Responder por voz", "Answer by voice", "Responder por voz");
        add("noMic", "Para responder por voz, permita o microfone para o {app} em Configurações > Apps da TV.",
                "To answer by voice, allow the microphone for {app} in the TV's Settings > Apps.",
                "Para responder por voz, permite el micrófono para {app} en Ajustes > Apps del televisor.");
        add("open", "OK abre o título", "OK opens the title", "OK abre el título");
        // The fixed questions (mood, who, film or series, what is in mind, length, origin, era), each skippable.
        add("skip", "Pular", "Skip", "Omitir");
        add("qMood", "Como você está se sentindo?", "How are you feeling?", "¿Cómo te sientes?");
        add("happy", "Feliz", "Happy", "Feliz");
        add("relaxed", "Relaxado", "Relaxed", "Relajado");
        add("sad", "Triste", "Sad", "Triste");
        add("tense", "Tenso", "Tense", "Tenso");
        add("excited", "Animado", "Excited", "Emocionado");
        add("curious", "Curioso", "Curious", "Curioso");
        add("romantic", "Romântico", "Romantic", "Romántico");
        add("nostalgic", "Nostálgico", "Nostalgic", "Nostálgico");
        add("qCompany", "Quem vai assistir?", "Who is watching?", "¿Quién ve?");
        add("alone", "Só eu", "Just me", "Solo yo");
        add("family", "Família", "Family", "Familia");
        add("friends", "Amigos", "Friends", "Amigos");
        add("qKind", "Filme ou série?", "A film or a series?", "¿Una película o una serie?");
        add("movie", "Um filme", "A film", "Una película");
        add("tv", "Uma série", "A series", "Una serie");
        add("any", "Tanto faz", "Either", "Cualquiera");
        add("qIdea", "O que você tem em mente? Escolha, escreva ou diga.", "What is on your mind? Pick, type or say it.",
                "¿Qué tienes en mente? Elige, escribe o dilo.");
        add("qLength", "Quanto tempo você tem?", "How much time do you have?", "¿Cuánto tiempo tienes?");
        add("short", "Curto (menos de 100 min)", "Short (under 100 min)", "Corto (menos de 100 min)");
        add("long", "Tenho tempo", "I have time", "Tengo tiempo");
        add("anyLength", "Qualquer duração", "Any length", "Cualquier duración");
        add("qOrigin", "De que língua ou país?", "Which language or origin?", "¿De qué idioma u origen?");
        add("o:ar", "Árabe", "Arabic", "Árabe");
        add("o:en", "Inglês", "English", "Inglés");
        add("o:tr", "Turco", "Turkish", "Turco");
        add("o:ko", "Coreano", "Korean", "Coreano");
        add("qEra", "De que época?", "From which era?", "¿De qué época?");
        add("e:new", "Recentes (últimos 5 anos)", "Recent (last 5 years)", "Recientes (últimos 5 años)");
        add("e:mid", "De 2000 a 2019", "2000 to 2019", "De 2000 a 2019");
        add("e:old", "Clássicos (antes de 2000)", "Classics (before 2000)", "Clásicos (antes de 2000)");
        // Why a title is on the list.
        add("youLike", "Você gosta de ", "You like ", "Te gusta ");
        add("fitsTime", "Cabe no seu tempo: ", "Fits your time: ", "Cabe en tu tiempo: ");
        add("highlyRated", "Muito bem avaliado: ", "Highly rated: ", "Muy bien valorado: ");
        add("popular", "Em alta", "Popular", "Popular");
        add("min", " min", " min", " min");
        // Genres, by the server's name.
        add("g:action", "Ação", "Action", "Acción");
        add("g:adventure", "Aventura", "Adventure", "Aventura");
        add("g:animation", "Animação", "Animation", "Animación");
        add("g:comedy", "Comédia", "Comedy", "Comedia");
        add("g:crime", "Crime", "Crime", "Crimen");
        add("g:documentary", "Documentário", "Documentary", "Documental");
        add("g:drama", "Drama", "Drama", "Drama");
        add("g:family", "Família", "Family", "Familia");
        add("g:fantasy", "Fantasia", "Fantasy", "Fantasía");
        add("g:history", "História", "History", "Historia");
        add("g:horror", "Terror", "Horror", "Terror");
        add("g:music", "Música", "Music", "Música");
        add("g:mystery", "Mistério", "Mystery", "Misterio");
        add("g:romance", "Romance", "Romance", "Romance");
        add("g:sci-fi", "Ficção científica", "Sci-Fi", "Ciencia ficción");
        add("g:thriller", "Suspense", "Thriller", "Suspenso");
        add("g:war", "Guerra", "War", "Bélico");
        add("g:western", "Faroeste", "Western", "Western");
    }

    static java.util.Set<String> ids() {
        return LINES.keySet();
    }

    static String english(String id) {
        return LINES.get(id)[1];
    }

    static String get(Tr tr, String id) {
        String[] l = LINES.get(id);
        if (l == null) throw new IllegalArgumentException("no line " + id);
        return tr.tr(l[0], l[1], l[2]);
    }

    /** The line in every language the TV speaks (Arabic through Arabic.java): what a spoken answer may match. */
    static String[] all(String id) {
        String[] l = LINES.get(id);
        return new String[] {l[0], l[1], l[2], Arabic.text(l[1])};
    }
}
