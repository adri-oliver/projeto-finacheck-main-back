package com.financheck.presentation;

import com.financheck.domain.entity.Categoria;
import com.financheck.domain.entity.PerfilFinanceiro;
import com.financheck.domain.entity.Transacao;
import com.financheck.domain.entity.enums.TipoTransacao;
import com.financheck.domain.repository.CategoriaRepository;
import com.financheck.domain.repository.PerfilFinanceiroRepository;
import com.financheck.presentation.dto.TransacaoDTO;
import com.financheck.usecase.perfil.ResolverPerfilFinanceiroUseCase;
import com.financheck.usecase.transacao.*;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/transacoes")
@RequiredArgsConstructor
public class TransacaoController {

    private final RegistrarTransacaoUseCase registrarTransacaoUseCase;
    private final AtualizarTransacaoUseCase atualizarTransacaoUseCase;
    private final RemoverTransacaoUseCase removerTransacaoUseCase;
    private final BuscarTransacoesPorPeriodoUseCase buscarTransacoesPorPeriodoUseCase;
    private final CalcularSaldoUseCase calcularSaldoUseCase;
    private final ResolverPerfilFinanceiroUseCase resolverPerfilFinanceiroUseCase;
    private final CategoriaRepository categoriaRepository;
    private final PerfilFinanceiroRepository perfilFinanceiroRepository;

    /**
     * Resposta enviada ao front: além do categoriaId, inclui o NOME da categoria
     * (campo "categoria"), que é o que a tela usa para exibir e escolher o ícone.
     */
    public record TransacaoResponse(
            Long id,
            TipoTransacao tipo,
            BigDecimal valor,
            LocalDate data,
            String descricao,
            Long categoriaId,
            String categoria,
            Long perfilId
    ) {}

    @PostMapping
    public ResponseEntity<TransacaoResponse> registrar(@RequestBody TransacaoDTO dto) {
        Long perfilResolvidoId = resolverPerfilFinanceiroUseCase.execute(dto.getPerfilId(), dto.getUsuarioId());

        Transacao transacao = new Transacao();
        transacao.setValor(resolverValorAbsoluto(dto));
        transacao.setTipo(resolverTipo(dto));
        transacao.setData(dto.getData() != null ? dto.getData() : LocalDate.now());
        transacao.setDescricao(resolverDescricao(dto));
        transacao.setCategoriaId(resolverOuCriarCategoria(dto, perfilResolvidoId));
        transacao.setPerfilId(perfilResolvidoId);

        Transacao salva = registrarTransacaoUseCase.execute(transacao);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(salva, new HashMap<>()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<TransacaoResponse> atualizar(@PathVariable Long id, @RequestBody TransacaoDTO dto) {
        Long perfilResolvidoId = resolverPerfilFinanceiroUseCase.execute(dto.getPerfilId(), dto.getUsuarioId());

        Transacao dados = new Transacao();
        dados.setValor(resolverValorAbsoluto(dto));
        dados.setTipo(resolverTipo(dto));
        dados.setData(dto.getData() != null ? dto.getData() : LocalDate.now());
        dados.setDescricao(resolverDescricao(dto));
        dados.setCategoriaId(resolverOuCriarCategoria(dto, perfilResolvidoId));
        dados.setPerfilId(perfilResolvidoId);

        Transacao atualizada = atualizarTransacaoUseCase.execute(id, dados);
        return ResponseEntity.ok(toResponse(atualizada, new HashMap<>()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remover(@PathVariable Long id) {
        removerTransacaoUseCase.execute(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public ResponseEntity<List<TransacaoResponse>> buscarPorPeriodo(
            @RequestParam(required = false) Long perfilId,
            @RequestParam(required = false) Long usuarioId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate inicio,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fim) {
        Long perfilResolvidoId = resolverPerfilFinanceiroUseCase.execute(perfilId, usuarioId);
        LocalDate dataInicio = inicio != null ? inicio : LocalDate.of(LocalDate.now().getYear(), 1, 1);
        LocalDate dataFim    = fim   != null ? fim   : LocalDate.of(LocalDate.now().getYear(), 12, 31);

        // cache de categorias para não consultar o banco repetidamente na mesma requisição
        Map<Long, String> cacheCategorias = new HashMap<>();

        List<TransacaoResponse> resposta = buscarTransacoesPorPeriodoUseCase
                .execute(perfilResolvidoId, dataInicio, dataFim)
                .stream()
                .map(t -> toResponse(t, cacheCategorias))
                .toList();

        return ResponseEntity.ok(resposta);
    }

    @GetMapping("/saldo")
    public ResponseEntity<BigDecimal> saldo(
            @RequestParam(required = false) Long perfilId,
            @RequestParam(required = false) Long usuarioId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate inicio,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fim) {
        Long perfilResolvidoId = resolverPerfilFinanceiroUseCase.execute(perfilId, usuarioId);
        LocalDate dataInicio = inicio != null ? inicio : LocalDate.of(LocalDate.now().getYear(), 1, 1);
        LocalDate dataFim    = fim   != null ? fim   : LocalDate.of(LocalDate.now().getYear(), 12, 31);
        return ResponseEntity.ok(calcularSaldoUseCase.execute(perfilResolvidoId, dataInicio, dataFim));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Converte a transação de domínio na resposta, preenchendo o nome da categoria. */
    private TransacaoResponse toResponse(Transacao t, Map<Long, String> cacheCategorias) {
        String nomeCategoria = cacheCategorias.computeIfAbsent(t.getCategoriaId(), id ->
                categoriaRepository.buscarPorId(id)
                        .map(Categoria::getNome)
                        .orElse("Outra"));

        return new TransacaoResponse(
                t.getId(),
                t.getTipo(),
                t.getValor(),
                t.getData(),
                t.getDescricao(),
                t.getCategoriaId(),
                nomeCategoria,
                t.getPerfilId()
        );
    }

    /** Valor sempre positivo no banco; o tipo carrega a semântica de débito/crédito. */
    private BigDecimal resolverValorAbsoluto(TransacaoDTO dto) {
        BigDecimal v = dto.getValor() != null ? dto.getValor() : dto.getAmount();
        if (v == null) throw new IllegalArgumentException("Campo 'valor' ou 'amount' é obrigatório");
        return v.abs();
    }

    /** Se o tipo não vier explícito, infere pelo sinal do valor original. */
    private TipoTransacao resolverTipo(TransacaoDTO dto) {
        if (dto.getTipo() != null) return dto.getTipo();
        BigDecimal v = dto.getValor() != null ? dto.getValor() : dto.getAmount();
        return (v != null && v.compareTo(BigDecimal.ZERO) < 0) ? TipoTransacao.DESPESA : TipoTransacao.RECEITA;
    }

    private String resolverDescricao(TransacaoDTO dto) {
        if (dto.getDescricao() != null) return dto.getDescricao();
        if (dto.getNome()     != null) return dto.getNome();
        return dto.getName();
    }

    /**
     * Resolve categoriaId pelo nome.
     * - Se veio categoriaId direto, usa ele.
     * - Caso contrário, busca pelo nome entre as categorias do usuário dono do perfil.
     * - Se não existir, cria a categoria automaticamente (evita erro 500).
     */
    private Long resolverOuCriarCategoria(TransacaoDTO dto, Long perfilResolvidoId) {
        if (dto.getCategoriaId() != null) return dto.getCategoriaId();

        String nomeCategoria = dto.getCategoria();
        if (nomeCategoria == null || nomeCategoria.isBlank()) {
            throw new IllegalArgumentException("Campo 'categoriaId' ou 'categoria' é obrigatório");
        }
        final String nome = nomeCategoria.trim();

        // Obtém o usuarioId a partir do perfil
        Long usuarioId = perfilFinanceiroRepository.buscarPorId(perfilResolvidoId)
                .map(PerfilFinanceiro::getUsuarioId)
                .orElseThrow(() -> new RuntimeException("Perfil não encontrado: " + perfilResolvidoId));

        // Procura categoria existente pelo nome (case-insensitive)
        return categoriaRepository.buscarPorUsuario(usuarioId).stream()
                .filter(c -> c.getNome().equalsIgnoreCase(nome))
                .map(Categoria::getId)
                .findFirst()
                .orElseGet(() -> {
                    // Cria a categoria automaticamente se não existir
                    Categoria nova = new Categoria();
                    nova.setNome(nome);
                    nova.setDescricao(nome);
                    nova.setUsuarioId(usuarioId);
                    return categoriaRepository.salvar(nova).getId();
                });
    }
}
