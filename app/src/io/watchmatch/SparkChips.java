package io.watchmatch;

/** The words on Spark's chips: what it understood, one part of a SparkQuery each (SparkQuery.parts()). */
final class SparkChips {
    private SparkChips() {}

    /** `title`: the title as found (Latin), shown for the "title" part. */
    static String label(SparkQuery q, String part, String title) {
        if (part.startsWith("genre:")) return genre(part.substring(6));
        switch (part) {
            case "kind":
                return SparkQuery.MOVIE.equals(q.kind) ? Ui.tr("Filmes", "Movies", "Películas", "أفلام")
                        : Ui.tr("Séries", "Series", "Series", "مسلسلات");
            case "origin": return origin(q.origin);
            case "era": return era(q);
            case "family": return Ui.tr("Para a família", "Family-safe", "Para la familia", "مناسب للعائلة");
            case "sort":
                return SparkQuery.TOP.equals(q.sort) ? Ui.tr("Mais bem avaliados", "Top rated", "Mejor valorados", "الأعلى تقييمًا")
                        : Ui.tr("Populares", "Popular", "Populares", "الأكثر مشاهدة");
            default: return "“" + Ui.isolate(title == null || title.isEmpty() ? q.title : title) + "”";
        }
    }

    private static String genre(String g) {
        switch (g) {
            case "action": return Ui.tr("Ação", "Action", "Acción", "أكشن");
            case "comedy": return Ui.tr("Comédia", "Comedy", "Comedia", "كوميديا");
            case "drama": return Ui.tr("Drama", "Drama", "Drama", "دراما");
            case "horror": return Ui.tr("Terror", "Horror", "Terror", "رعب");
            case "romance": return Ui.tr("Romance", "Romance", "Romance", "رومانسي");
            case "crime": return Ui.tr("Crime", "Crime", "Crimen", "جريمة");
            case "thriller": return Ui.tr("Suspense", "Thriller", "Suspenso", "إثارة");
            case "war": return Ui.tr("Guerra", "War", "Bélico", "حرب");
            case "scifi": return Ui.tr("Ficção científica", "Sci-Fi", "Ciencia ficción", "خيال علمي");
            case "fantasy": return Ui.tr("Fantasia", "Fantasy", "Fantasía", "فانتازيا");
            case "animation": return Ui.tr("Animação", "Animation", "Animación", "رسوم متحركة");
            case "documentary": return Ui.tr("Documentário", "Documentary", "Documental", "وثائقي");
            case "family": return Ui.tr("Família", "Family", "Familia", "عائلي");
            case "history": return Ui.tr("História", "History", "Historia", "تاريخي");
            case "mystery": return Ui.tr("Mistério", "Mystery", "Misterio", "غموض");
            case "adventure": return Ui.tr("Aventura", "Adventure", "Aventura", "مغامرة");
            default: return g;
        }
    }

    private static String origin(String o) {
        switch (o) {
            case SparkQuery.ARABIC: return Ui.tr("Árabes", "Arabic", "Árabes", "عربية");
            case SparkQuery.TURKISH: return Ui.tr("Turcas", "Turkish", "Turcas", "تركية");
            case SparkQuery.KOREAN: return Ui.tr("Coreanas", "Korean", "Coreanas", "كورية");
            case SparkQuery.HINDI: return Ui.tr("Indianas", "Indian", "Indias", "هندية");
            case SparkQuery.JAPANESE: return Ui.tr("Japonesas", "Japanese", "Japonesas", "يابانية");
            case SparkQuery.FOREIGN: return Ui.tr("Estrangeiras", "Foreign", "Extranjeras", "أجنبية");
            case SparkQuery.AMERICAN: return Ui.tr("Americanas", "American", "Estadounidenses", "أمريكية");
            case SparkQuery.EGYPTIAN: return Ui.tr("Egípcias", "Egyptian", "Egipcias", "مصرية");
            case SparkQuery.GULF: return Ui.tr("Do Golfo", "Gulf", "Del Golfo", "خليجية");
            case "en": return Ui.tr("Inglesas", "English", "Inglesas", "إنجليزية");
            case "fr": return Ui.tr("Francesas", "French", "Francesas", "فرنسية");
            case "de": return Ui.tr("Alemãs", "German", "Alemanas", "ألمانية");
            case "es": return Ui.tr("Espanholas", "Spanish", "Españolas", "إسبانية");
            case "zh": return Ui.tr("Chinesas", "Chinese", "Chinas", "صينية");
            case "ru": return Ui.tr("Russas", "Russian", "Rusas", "روسية");
            default: return o.toUpperCase(java.util.Locale.ROOT);
        }
    }

    private static String era(SparkQuery q) {
        switch (q.era) {
            case SparkQuery.NEW: return Ui.tr("Novos", "New", "Nuevos", "جديدة");
            case SparkQuery.OLD: return Ui.tr("Antigos", "Old", "Antiguos", "قديمة");
            case SparkQuery.DECADE: return decade(q.from);
            case SparkQuery.YEAR: return String.valueOf(q.from);
            case SparkQuery.AFTER: return Ui.tr("Desde ", "From ", "Desde ", "منذ ") + q.from;
            case SparkQuery.BEFORE: return Ui.tr("Antes de ", "Before ", "Antes de ", "قبل ") + (q.to + 1);
            default: return q.from + "–" + q.to;
        }
    }

    /** "1990s", "Anos 90", "التسعينات". */
    private static String decade(int from) {
        String ar;
        switch (from) {
            case 1960: ar = "الستينات"; break;
            case 1970: ar = "السبعينات"; break;
            case 1980: ar = "الثمانينات"; break;
            case 1990: ar = "التسعينات"; break;
            case 2000: ar = "الألفينات"; break;
            default: ar = "عقد " + from; break;
        }
        String short_ = from < 2000 ? String.valueOf(from % 100) : String.valueOf(from);
        return Ui.tr("Anos " + short_, from + "s", "Años " + short_, ar);
    }
}
