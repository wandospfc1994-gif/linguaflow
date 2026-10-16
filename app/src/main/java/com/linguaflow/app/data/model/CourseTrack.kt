package com.linguaflow.app.data.model

sealed class Exercise {
    abstract val id: String
    abstract val title: String

    data class MultipleChoice(
        override val id: String,
        override val title: String = "Múltipla Escolha",
        val question: String,
        val options: List<String>,
        val correctIndex: Int,
        val explanation: String
    ) : Exercise()

    data class SentenceBuilder(
        override val id: String,
        override val title: String = "Montagem de Frase",
        val promptPt: String,
        val scrambledWords: List<String>,
        val targetSentence: String,
        val explanation: String
    ) : Exercise()

    data class ListeningSpeech(
        override val id: String,
        override val title: String = "Escuta e Pronúncia",
        val englishSentence: String,
        val phoneticGuide: String,
        val translationPt: String,
        val tip: String
    ) : Exercise()
}

data class DailyChallengeSet(
    val id: Int,
    val title: String,
    val category: String,
    val xpReward: Int = 100,
    val exercises: List<Exercise>
)

data class CefrLevelInfo(
    val code: String,
    val name: String,
    val subtitle: String,
    val description: String,
    val minXp: Int,
    val nextLevelXp: Int,
    val baseFluencyPercent: Int,
    val competencies: List<String>,
    val grammarFocus: List<String>,
    val sampleSentence: String,
    val sampleTranslation: String
)

object CourseTrack {

    val levels: List<CefrLevelInfo> = listOf(
        CefrLevelInfo(
            code = "A1",
            name = "Iniciante (Breakthrough)",
            subtitle = "Fundamentos da Comunicação",
            description = "Compreende e usa expressões cotidianas e frases básicas para suprir necessidades imediatas. Consegue se apresentar e responder perguntas simples.",
            minXp = 0,
            nextLevelXp = 300,
            baseFluencyPercent = 15,
            competencies = listOf(
                "Apresentação pessoal (nome, idade, país)",
                "Saudações formais e informais",
                "Fazer pedidos em cafeterias e restaurantes",
                "Soletrar e números básicos"
            ),
            grammarFocus = listOf(
                "Verb To Be (am / is / are)",
                "Pronomes Pessoais (I, you, he, she...)",
                "Artigos indefinidos (a / an)",
                "Present Simple para rotinas diárias"
            ),
            sampleSentence = "Hello, my name is Alex and I am learning English.",
            sampleTranslation = "Olá, meu nome é Alex e eu estou aprendendo inglês."
        ),
        CefrLevelInfo(
            code = "A2",
            name = "Básico (Waystage)",
            subtitle = "Autonomia em Situações Cotidianas",
            description = "Comunica-se em tarefas rotineiras com troca de informações diretas. Consegue descrever aspectos do passado, ambiente pessoal e necessidades básicas.",
            minXp = 300,
            nextLevelXp = 800,
            baseFluencyPercent = 35,
            competencies = listOf(
                "Contar experiências passadas e finais de semana",
                "Comprar roupas e pedir orientações de direção",
                "Falar sobre planos futuros e viagens",
                "Descrever gostos, hobbies e rotinas familiares"
            ),
            grammarFocus = listOf(
                "Past Simple (regular e irregular)",
                "Futuro com 'Going to' e 'Will'",
                "Comparativos e Superlativos (bigger, the best)",
                "Preposições de tempo e lugar (in, on, at)"
            ),
            sampleSentence = "Yesterday I visited a museum and bought some gifts.",
            sampleTranslation = "Ontem eu visitei um museu e comprei alguns presentes."
        ),
        CefrLevelInfo(
            code = "B1",
            name = "Intermediário (Threshold)",
            subtitle = "Independência e Expressão Pessoal",
            description = "Compreende pontos principais de assuntos familiares como trabalho, estudos e lazer. Consegue lidar com a maioria das situações em viagens.",
            minXp = 800,
            nextLevelXp = 1600,
            baseFluencyPercent = 60,
            competencies = listOf(
                "Expressar opiniões e justificar pontos de vista",
                "Descrever sonhos, ambições e eventos inesperados",
                "Resolver imprevistos em hotéis, aeroportos ou compras",
                "Escrever e-mails informais e mensagens estruturadas"
            ),
            grammarFocus = listOf(
                "Present Perfect (have you ever...?)",
                "Modal Verbs (should, must, might, could)",
                "First & Second Conditionals (If I had... I would)",
                "Conectivos de discurso (however, although, therefore)"
            ),
            sampleSentence = "If I have enough free time this year, I will travel abroad.",
            sampleTranslation = "Se eu tiver tempo livre suficiente este ano, vou viajar para o exterior."
        ),
        CefrLevelInfo(
            code = "B2",
            name = "Intermediário Superior (Vantage)",
            subtitle = "Fluência Espontânea e Profissional",
            description = "Compreende ideias principais de textos complexos. Comunica-se com espontaneidade com nativos sem tensão para ambos os lados.",
            minXp = 1600,
            nextLevelXp = 3000,
            baseFluencyPercent = 80,
            competencies = listOf(
                "Participar ativamente de reuniões e debates de negócios",
                "Explicar prós e contras de diferentes opções de decisão",
                "Compreender filmes e podcasts em inglês com gírias reais",
                "Negociar e apresentar soluções para problemas complexos"
            ),
            grammarFocus = listOf(
                "Third Conditional & Mixed Conditionals",
                "Passive Voice em contextos formais",
                "Phrasal Verbs avançados e collocations",
                "Relative Clauses (defining & non-defining)"
            ),
            sampleSentence = "Had we planned ahead, the whole project would have run smoothly.",
            sampleTranslation = "Se tivéssemos planejado com antecedência, o projeto todo teria corrido perfeitamente."
        ),
        CefrLevelInfo(
            code = "C1",
            name = "Avançado (Operational)",
            subtitle = "Domínio Pleno e Expressão Flexível",
            description = "Compreende uma vasta gama de textos longos e exigentes, reconhecendo significados implícitos. Usa a língua de modo flexível para fins sociais, acadêmicos e profissionais.",
            minXp = 3000,
            nextLevelXp = 5000,
            baseFluencyPercent = 95,
            competencies = listOf(
                "Expressão fluida sem busca evidente de vocabulário",
                "Argumentação sofisticada e artigos acadêmicos/técnicos",
                "Compreensão de ironia, humor refinado e metáforas",
                "Liderança de projetos multiculturais de alta complexidade"
            ),
            grammarFocus = listOf(
                "Inversões enfáticas (Seldom have I seen...)",
                "Subjuntivo em inglês (I insist that he be...)",
                "Nuances estilísticas e registro formal vs coloquial",
                "Expressões idiomáticas de nível nativo"
            ),
            sampleSentence = "Rarely do we encounter an opportunity that combines innovation with scalability.",
            sampleTranslation = "Raramente encontramos uma oportunidade que combine inovação com escalabilidade."
        )
    )

    fun getLevelInfo(levelCode: String): CefrLevelInfo {
        return levels.firstOrNull { it.code.equals(levelCode, ignoreCase = true) } ?: levels.first()
    }

    fun calculateLevelForXp(xp: Int): Pair<String, Int> {
        val lvl = levels.lastOrNull { xp >= it.minXp } ?: levels.first()
        return Pair(lvl.code, lvl.nextLevelXp)
    }

    fun calculateFluencyPercentage(xp: Int, accuracyPercentage: Int): Int {
        val (levelCode, nextXp) = calculateLevelForXp(xp)
        val info = getLevelInfo(levelCode)
        val xpInLevel = (xp - info.minXp).coerceAtLeast(0)
        val levelSpan = (nextXp - info.minXp).coerceAtLeast(1)
        val intraLevelProgress = (xpInLevel.toFloat() / levelSpan.toFloat()).coerceIn(0f, 1f)

        val nextLevelPercent = levels.getOrNull(levels.indexOf(info) + 1)?.baseFluencyPercent ?: 100
        val baseSpread = nextLevelPercent - info.baseFluencyPercent

        val rawEstimated = info.baseFluencyPercent + (intraLevelProgress * baseSpread).toInt()
        val accuracyAdjusted = ((rawEstimated * 0.75f) + (accuracyPercentage * 0.25f)).toInt()
        return accuracyAdjusted.coerceIn(10, 99)
    }

    val challengeSets: List<DailyChallengeSet> = listOf(
        DailyChallengeSet(
            id = 1,
            title = "Desafio 1: Conversação Diária & Pedidos",
            category = "Restaurante e Cafeteria",
            xpReward = 100,
            exercises = listOf(
                Exercise.MultipleChoice(
                    id = "mc_1",
                    question = "Qual é a forma mais educada de pedir um café em inglês?",
                    options = listOf(
                        "I want a coffee now.",
                        "I would like a coffee, please.",
                        "Give me one coffee.",
                        "Coffee is for me."
                    ),
                    correctIndex = 1,
                    explanation = "'I would like...' (ou contraído 'I'd like...') é a forma mais cortês e comum em ambientes reais."
                ),
                Exercise.SentenceBuilder(
                    id = "sb_1",
                    promptPt = "Traduza: 'Eu gostaria de uma mesa perto da janela.'",
                    scrambledWords = listOf("please", "window", "would", "a", "table", "the", "I", "near", "like"),
                    targetSentence = "I would like a table near the window please",
                    explanation = "Estrutura correta: Sujeito (I) + would like + objeto (a table) + preposição (near the window) + cortesia (please)."
                ),
                Exercise.ListeningSpeech(
                    id = "ls_1",
                    englishSentence = "Could you bring me the bill, please?",
                    phoneticGuide = "/kʊd juː brɪŋ miː ðə bɪl pliːz/",
                    translationPt = "Você poderia me trazer a conta, por favor?",
                    tip = "No inglês americano usa-se 'the check', e no britânico 'the bill'. Ambas são perfeitamente aceitas!"
                )
            )
        ),
        DailyChallengeSet(
            id = 2,
            title = "Desafio 2: Rotina e Hábitos de Trabalho",
            category = "Carreira e Produtividade",
            xpReward = 100,
            exercises = listOf(
                Exercise.MultipleChoice(
                    id = "mc_2",
                    question = "Qual a preposição correta: 'I usually start working ___ 9 AM.'?",
                    options = listOf("in", "on", "at", "for"),
                    correctIndex = 2,
                    explanation = "Para horários específicos (horas do relógio), usa-se sempre 'at' (at 9 AM, at noon, at midnight)."
                ),
                Exercise.SentenceBuilder(
                    id = "sb_2",
                    promptPt = "Traduza: 'Ela sempre verifica os e-mails pela manhã.'",
                    scrambledWords = listOf("checks", "in", "the", "her", "always", "emails", "She", "morning"),
                    targetSentence = "She always checks her emails in the morning",
                    explanation = "Advérbios de frequência como 'always' vêm antes do verbo principal: 'She always checks...'."
                ),
                Exercise.ListeningSpeech(
                    id = "ls_2",
                    englishSentence = "I need to schedule a quick meeting with the team.",
                    phoneticGuide = "/aɪ niːd tuː ˈskɛdʒuːl ə kwɪk ˈmiːtɪŋ wɪð ðə tiːm/",
                    translationPt = "Preciso agendar uma reunião rápida com a equipe.",
                    tip = "'Schedule' pode ser pronunciado 'SKED-jool' (EUA) ou 'SHED-yool' (Reino Unido)."
                )
            )
        ),
        DailyChallengeSet(
            id = 3,
            title = "Desafio 3: Viagens, Aeroporto e Direções",
            category = "Viagens Internacionais",
            xpReward = 100,
            exercises = listOf(
                Exercise.MultipleChoice(
                    id = "mc_3",
                    question = "No aeroporto, onde você despacha a bagagem de porão?",
                    options = listOf(
                        "Duty-free shop",
                        "Baggage drop-off / Check-in counter",
                        "Boarding gate",
                        "Security check"
                    ),
                    correctIndex = 1,
                    explanation = "O 'Baggage drop-off' ou balcão de check-in é o local onde malas despachadas são entregues à companhia."
                ),
                Exercise.SentenceBuilder(
                    id = "sb_3",
                    promptPt = "Traduza: 'Onde fica o portão de embarque para Londres?'",
                    scrambledWords = listOf("for", "Where", "boarding", "gate", "London", "the", "is", "?"),
                    targetSentence = "Where is the boarding gate for London ?",
                    explanation = "'Where is the boarding gate for [destino]?' é a pergunta padrão nos aeroportos internacionais."
                ),
                Exercise.ListeningSpeech(
                    id = "ls_3",
                    englishSentence = "Excuse me, how do I get to the train station?",
                    phoneticGuide = "/ɪkˈskjuːz miː haʊ duː aɪ ɡɛt tuː ðə treɪn ˈsteɪʃən/",
                    translationPt = "Com licença, como eu chego à estação de trem?",
                    tip = "'How do I get to...' é a expressão mais natural para pedir direções em qualquer cidade de língua inglesa."
                )
            )
        ),
        DailyChallengeSet(
            id = 4,
            title = "Desafio 4: Opiniões, Debates e Nuances",
            category = "Expressão e Argumentação",
            xpReward = 100,
            exercises = listOf(
                Exercise.MultipleChoice(
                    id = "mc_4",
                    question = "Como concordar enfaticamente com uma opinião de forma natural?",
                    options = listOf(
                        "I am agree.",
                        "I couldn't agree more.",
                        "I am with complete agreement.",
                        "Agree is my thought."
                    ),
                    correctIndex = 1,
                    explanation = "'I couldn't agree more' significa 'Não poderia concordar mais' (concordância total). Lembre-se: nunca diga 'I am agree'!"
                ),
                Exercise.SentenceBuilder(
                    id = "sb_4",
                    promptPt = "Traduza: 'Na minha opinião, esta solução é muito mais eficiente.'",
                    scrambledWords = listOf("is", "opinion", "In", "much", "more", "my", "efficient", "this", "solution"),
                    targetSentence = "In my opinion this solution is much more efficient",
                    explanation = "'In my opinion...' introduz seu ponto de vista com elegância e clareza."
                ),
                Exercise.ListeningSpeech(
                    id = "ls_4",
                    englishSentence = "That makes a lot of sense from a practical perspective.",
                    phoneticGuide = "/ðæt meɪks ə lɒt ɒv sɛns frɒm ə ˈpræktɪkəl pəˈspɛktɪv/",
                    translationPt = "Isso faz muito sentido sob uma perspectiva prática.",
                    tip = "'That makes sense' é uma das frases mais usadas no dia a dia corporativo e pessoal."
                )
            )
        )
    )
}
