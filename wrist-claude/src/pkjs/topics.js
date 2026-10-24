// {topic} placeholder for quick prompts: a random seed word plus a random angle, so the same
// prompt ("Tell me a fun fact about {topic}.") gives a different answer each press. ES5 only.
// Keep WORDS and ANGLES identical to core/QuickTopics.kt in the Android companion.

var PLACEHOLDER = '{topic}';
var MAX_RECENT = 5;
var MAX_RECENT_CHARS = 120;

var WORDS = (
  'octopus|jellyfish|shark|whale|dolphin|seahorse|starfish|coral|lobster|crab|squid|eel|walrus|' +
  'seal|penguin|pelican|albatross|puffin|flamingo|owl|eagle|falcon|hummingbird|crow|parrot|peacock|' +
  'ostrich|emu|kiwi|pigeon|swan|woodpecker|vulture|bat|squirrel|beaver|otter|raccoon|skunk|' +
  'hedgehog|porcupine|mole|rabbit|hare|kangaroo|koala|platypus|wombat|sloth|armadillo|anteater|' +
  'giraffe|zebra|elephant|rhinoceros|hippopotamus|camel|llama|alpaca|bison|moose|reindeer|wolf|fox|' +
  'bear|panda|tiger|lion|cheetah|leopard|jaguar|hyena|meerkat|gorilla|chimpanzee|orangutan|lemur|' +
  'capybara|tapir|yak|goat|sheep|cow|pig|horse|donkey|chicken|duck|goose|turkey|frog|toad|' +
  'salamander|axolotl|newt|turtle|tortoise|crocodile|alligator|chameleon|gecko|iguana|' +
  'Komodo dragon|snake|python|cobra|ant|bee|wasp|butterfly|moth|dragonfly|firefly|ladybug|beetle|' +
  'cricket|grasshopper|termite|mosquito|spider|scorpion|snail|slug|earthworm|tardigrade|mushroom|' +
  'moss|fern|cactus|bamboo|oak|redwood|baobab|maple|pine|willow|palm|sunflower|tulip|rose|orchid|' +
  'lotus|dandelion|clover|ivy|seaweed|algae|lichen|mangrove|apple|banana|orange|lemon|lime|' +
  'grapefruit|mango|pineapple|coconut|avocado|tomato|potato|carrot|onion|garlic|ginger|pepper|' +
  'chili|cucumber|pumpkin|watermelon|strawberry|blueberry|raspberry|cherry|grape|peach|plum|pear|' +
  'fig|dates (the fruit)|olive|almond|peanut|walnut|cashew|pistachio|chestnut|rice|wheat|corn|oats|' +
  'barley|quinoa|bread|pasta|noodles|pizza|sushi|taco|burrito|dumpling|croissant|bagel|pretzel|' +
  'pancake|waffle|cheese|butter|yogurt|milk|egg|honey|sugar|salt|vinegar|mustard|ketchup|' +
  'mayonnaise|soy sauce|chocolate|vanilla|cinnamon|nutmeg|saffron|cardamom|coffee|tea|cocoa|' +
  'lemonade|soda|popcorn|chewing gum|marshmallow|licorice|candy|ice cream|cake|cookie|pie|donut|' +
  'toast|soup|curry|sandwich|hamburger|hotdog|sausage|bacon|salmon|tuna|sardine|oyster|shrimp|tofu|' +
  'kimchi|pickle|jam|peanut butter|cereal|sun|moon|Mercury|Venus|Earth|Mars|Jupiter|Saturn|Uranus|' +
  'Neptune|Pluto|comet|asteroid|meteor|galaxy|nebula|black hole|stars|supernova|quasar|pulsar|' +
  'eclipse|aurora|gravity|orbit|telescope|satellite|rocket|astronaut|spacesuit|space station|rover|' +
  'sunspot|solar wind|Milky Way|constellation|volcano|earthquake|tsunami|glacier|iceberg|desert|' +
  'rainforest|savanna|tundra|canyon|cave|waterfall|geyser|river|lake|ocean|sea|island|peninsula|' +
  'reef|mountain|hill|valley|cliff|beach|dune|swamp|marsh|delta|lagoon|fjord|plateau|crater|' +
  'hot spring|quicksand|sand|mud|clay|rocks|granite|marble|diamond|gold|silver|copper|iron|' +
  'aluminum|lead (the metal)|tin (the metal)|zinc|platinum|quartz|amethyst|ruby|emerald|sapphire|' +
  'pearl|amber|fossil|dinosaur|mammoth|meteorite|salt flat|rain|snow|hail|fog|cloud|lightning|' +
  'thunder|tornado|hurricane|rainbow|wind|frost|dew|drought|monsoon|heatwave|blizzard|humidity|' +
  'temperature|season|equinox|solstice|tide|ocean currents|heart|brain|lung|liver|kidney|stomach|' +
  'skin|bone|muscle|blood|tooth|tongue|eye|ear|nose|hair|fingernail|fingerprint|sneeze|hiccup|yawn|' +
  'sleep|dream|memory|laughter|tears|sweat|goosebumps|heartbeat|DNA|gene|cell|bacteria|virus|' +
  'immune system|vaccine|hormone|adrenaline|dopamine|vitamin|calcium|protein|appetite|taste|smell|' +
  'reflex|water|fire|ice|steam|air|oxygen|hydrogen|carbon|nitrogen|helium|neon|sodium|magnet|' +
  'electricity|battery|light|sound|color|heat|friction|rust|glass|plastic|rubber|paper|cotton|silk|' +
  'wool|leather|nylon|steel|concrete|brick|wood|cork|wax|soap|bubble|foam|crystal|atom|molecule|' +
  'acid|laser|radio|X-ray|microwave|wheel|bicycle|car|bus|train|subway|tram|airplane|helicopter|' +
  'hot air balloon|blimp|ship|submarine|canoe|sailboat|ferry|truck|tractor|motorcycle|skateboard|' +
  'scooter|elevator|escalator|bridge|tunnel|road|highway|traffic light|parking meter|lighthouse|' +
  'canal|dam|windmill|clock|calendar|compass|map|globe|telephone|smartphone|computer|keyboard|' +
  'computer mouse|internet|email|television|camera|photograph|film|video game|robot|calculator|' +
  'printer|typewriter|microphone|headphones|loudspeaker|vinyl record|cassette|radio tower|barcode|' +
  'QR code|password|emoji|pencil|pen|eraser|ruler|scissors|stapler|paperclip|envelope|stamp|' +
  'postcard|book|library|newspaper|magazine|dictionary|encyclopedia|alphabet|comma|question mark|' +
  'punctuation|handwriting|ink|crayon|chalk|paint|canvas|sculpture|museum|statue|mosaic|origami|' +
  'guitar|piano|violin|cello|drum|trumpet|saxophone|flute|harp|accordion|bagpipes|harmonica|' +
  'ukulele|banjo|xylophone|orchestra|choir|opera|jazz|blues|rock and roll|hip hop|lullaby|' +
  'national anthem|karaoke|metronome|soccer|basketball|baseball|football|tennis|golf|hockey|' +
  'the sport of cricket|rugby|volleyball|badminton|table tennis|bowling|boxing|wrestling|judo|' +
  'karate|fencing|archery|skiing|snowboarding|surfing|swimming|diving|marathon|sprint|high jump|' +
  'pole vault|the Olympics|chess|checkers|poker|dice|dominoes|jigsaw puzzle|crossword|sudoku|' +
  'Rubik\'s cube|yo-yo|kite|frisbee|marbles|lego|teddy bear|doll|balloon|fireworks|castle|pyramid|' +
  'sphinx|the Colosseum|Great Wall of China|Stonehenge|Eiffel Tower|Statue of Liberty|Big Ben|' +
  'Taj Mahal|temple|cathedral|mosque|skyscraper|igloo|treehouse|cabin|tent|lighthouse keeper|' +
  'palace|fortress|moat|drawbridge|tower|dome|arch|staircase|window|door|roof|chimney|fireplace|' +
  'basement|attic|garden|fountain|maze|knight|samurai|viking|pirate|pharaoh|gladiator|cowboy|ninja|' +
  'explorer|inventor|astronomer|scientist|doctor|nurse|firefighter|chef|baker|farmer|fisherman|' +
  'blacksmith|carpenter|teacher|librarian|detective|spy|magician|clown|juggler|acrobat|mime|poet|' +
  'painter|coin|banknote|credit card|bank|tax|market|auction|shopping cart|vending machine|receipt|' +
  'price tag|piggy bank|lottery|stock market|crown|throne|flag|passport|border|map maker|census|' +
  'election|parliament|constitution|court|jury|law|police|prison|postal service|telegram|' +
  'morse code|semaphore|hat|shoe|sock|glove|scarf|umbrella|sunglasses|button|zipper|velcro|pocket|' +
  'necklace|finger ring|earring|wristwatch|backpack|suitcase|wallet|belt|tie|jeans|T-shirt|pajamas|' +
  'kimono|kilt|sandal|boot|sneaker|bed|pillow|blanket|chair|table|sofa|lamp|candle|mirror|carpet|' +
  'curtain|bathtub|shower|toilet|sink|refrigerator|oven|microwave oven|toaster|kettle|blender|fork|' +
  'spoon|knife|chopsticks|plate|bowl|cup|mug|teapot|bottle|jar|can|lid|sponge|broom|vacuum cleaner|' +
  'washing machine|clothes iron|key|lock|doorbell|birthday|wedding|holiday|New Year\'s|Halloween|' +
  'Valentine\'s Day|Thanksgiving|festival|carnival|parade|circus|zoo|aquarium|amusement park|' +
  'roller coaster|ferris wheel|carousel|picnic|campfire|sleepwalking|left handedness|yawning|' +
  'blushing|whistling|sign language|braille|lie detector|handshake|high five|wink|smile|number|' +
  'zero|pi|infinity|prime number|fraction|decimal|percentage|triangle|circle|square|hexagon|spiral|' +
  'pentagon|cube|sphere|Fibonacci numbers|golden ratio|probability|statistics|abacus|binary|' +
  'language|word|name|surname|nickname|slang|tongue twisters|palindrome|pun|riddle|joke|proverb|' +
  'idiom|accent|dialect|Esperanto|Latin|hieroglyphs|runes|cheddar|parmesan|mozzarella|brie|gouda|' +
  'feta|tea ceremony|tapas|pho|ramen|paella|risotto|falafel|hummus|kebab|bratwurst|baguette|' +
  'tortilla|churro|gelato|baklava|mochi').split('|');

var ANGLES = [
  'Focus on its history.',
  'Focus on the science behind it.',
  'Pick something most people get wrong about it.',
  'Include a surprising number or record.',
  'Connect it to another country or culture.',
  'Make it about how it got its name.',
  'Pick something weird or unexpected.',
  'Pick a fact a 10-year-old would love.',
  'Focus on a famous person or event connected to it.',
  'Compare it to something from everyday life.',
  'Pick an obscure fact, not a famous one.',
  'Focus on how it has changed over time.'
];

function pick(arr, rand) {
  var i = Math.floor(rand() * arr.length);
  return arr[i < 0 ? 0 : (i >= arr.length ? arr.length - 1 : i)];
}

function hasTopic(prompt) {
  return typeof prompt === 'string' && prompt.indexOf(PLACEHOLDER) >= 0;
}

// Fills {topic} and adds an angle plus the recent answers to avoid. Prompts without the
// placeholder come back unchanged. rand defaults to Math.random (tests pass their own).
function expand(prompt, recent, rand) {
  if (!hasTopic(prompt)) { return prompt; }
  rand = rand || Math.random;
  var word = pick(WORDS, rand);
  var out = prompt.split(PLACEHOLDER).join(word) + ' ' + pick(ANGLES, rand) +
    ' If nothing good fits, use a closely related subject. Keep it true.';
  var avoid = [];
  for (var i = 0; recent && i < recent.length; i++) {
    if (recent[i]) { avoid.push('"' + String(recent[i]).replace(/\s+/g, ' ').substring(0, MAX_RECENT_CHARS) + '"'); }
  }
  if (avoid.length > 0) { out += ' Do not repeat any of these: ' + avoid.join('; '); }
  return out;
}

// Returns the recent-answers list with answer added, newest last, at most MAX_RECENT long.
function remember(recent, answer) {
  var list = (recent || []).concat([answer]);
  return list.slice(-MAX_RECENT);
}

module.exports = {
  PLACEHOLDER: PLACEHOLDER,
  MAX_RECENT: MAX_RECENT,
  WORDS: WORDS,
  ANGLES: ANGLES,
  hasTopic: hasTopic,
  expand: expand,
  remember: remember
};
